package com.example.service.ai.impl;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AgentRunResponse;
import com.example.exception.BusinessException;
import com.example.exception.ConflictException;
import com.example.service.ai.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** MySQL 为执行事实的依据。重复提交只能查结果，不能再次调用模型。 */
@Service
public class JdbcAgentRunService implements AgentRunService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcAgentRunService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        // 审计意图必须先提交，不能随着业务事务回滚而消失。
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    @Override
    public Start begin(Long userId, AiChatRequest request) {
        String fingerprint = fingerprint(request.getConversationId() + "\n" + request.getMessage());
        String runId = UUID.randomUUID().toString();
        try {
            transaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO agent_runs(run_id,user_id,client_request_id,conversation_id,
                        request_fingerprint,user_message,status) VALUES (?,?,?,?,?,?,'RUNNING')
                    """, runId, userId, request.getClientRequestId(), request.getConversationId(), fingerprint, request.getMessage()));
            return new Start(runId, null);
        } catch (DuplicateKeyException duplicate) {
            var fingerprints = jdbc.queryForList("SELECT request_fingerprint FROM agent_runs WHERE user_id=? AND client_request_id=?",
                    String.class, userId, request.getClientRequestId());
            if (fingerprints.isEmpty() || !fingerprint.equals(fingerprints.getFirst())) {
                throw new ConflictException("同一请求标识不能用于不同的消息或会话");
            }
            AgentRunResponse previous = find(userId, request.getClientRequestId());
            if (previous.response() != null) return new Start(previous.runId(), previous.response());
            throw new ConflictException("请求已受理，当前状态为 " + previous.status() + "；请查询执行结果，不要重复提交");
        }
    }

    @Override
    public void finish(Long userId, String runId, AiChatResponse response) {
        String state = response.successful() ? "SUCCEEDED" : "FAILED";
        int changed = jdbc.update("""
                UPDATE agent_runs SET status=?,response_json=?,error_code=?,finished_at=CURRENT_TIMESTAMP(6)
                WHERE user_id=? AND run_id=? AND status='RUNNING'
                """, state, encode(response), response.successful() ? null : response.resultStatus().name(), userId, runId);
        if (changed != 1) throw new ConflictException("任务已中断，请查询实际执行结果");
    }

    @Override
    public void interrupt(Long userId, String runId, String reason) {
        jdbc.update("""
                UPDATE agent_runs SET status='INTERRUPTED',error_code=?,finished_at=CURRENT_TIMESTAMP(6)
                WHERE user_id=? AND run_id=? AND status='RUNNING'
                """, reason, userId, runId);
    }

    @Override
    public void heartbeat(Long userId, String runId) {
        jdbc.update("UPDATE agent_runs SET heartbeat_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND run_id=? AND status='RUNNING'",
                userId, runId);
    }

    @Override
    public void startStep(Long userId, String runId, int sequence, String toolName, String arguments) {
        transaction.executeWithoutResult(tx -> {
            // 和中断/完成串行化：终态之后禁止启动新的工具，已在途的工具仍可能完成。
            var states = jdbc.queryForList("SELECT status FROM agent_runs WHERE user_id=? AND run_id=? FOR UPDATE",
                    String.class, userId, runId);
            if (states.isEmpty() || !"RUNNING".equals(states.getFirst())) throw new ConflictException("任务已停止，不允许继续调用工具");
            jdbc.update("INSERT INTO agent_run_steps(run_id,sequence_no,tool_name,arguments_json,status) VALUES (?,?,?,?,'RUNNING')",
                    runId, sequence, toolName, arguments == null ? "{}" : arguments);
            heartbeat(userId, runId);
        });
    }

    @Override
    public void finishStep(Long userId, String runId, int sequence, String outcome, Object result) {
        String state = AgentRecoveryService.stepState(outcome, result);
        // 迟到的业务结果仍需保存，即使任务已经超时/取消；UNKNOWN 不等价于回滚。
        jdbc.update("""
                UPDATE agent_run_steps s JOIN agent_runs r ON r.run_id=s.run_id
                SET s.status=?,s.outcome=?,s.result_json=?,s.finished_at=CURRENT_TIMESTAMP(6)
                WHERE r.user_id=? AND s.run_id=? AND s.sequence_no=? AND s.status='RUNNING' AND s.retry_count=0
                """, state, outcome, result == null ? null : encode(result), userId, runId, sequence);
        heartbeat(userId, runId);
    }

    @Override
    public AgentRunResponse find(Long userId, String clientRequestId) {
        // 进程崩溃后不抢占重跑；按数据库时钟标为中断。活跃流每 15 秒续心跳。
        jdbc.update("""
                UPDATE agent_runs SET status='INTERRUPTED',error_code='HEARTBEAT_EXPIRED',finished_at=CURRENT_TIMESTAMP(6)
                WHERE user_id=? AND client_request_id=? AND status='RUNNING'
                  AND heartbeat_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE)
                """, userId, clientRequestId);
        var runs = jdbc.query("SELECT * FROM agent_runs WHERE user_id=? AND client_request_id=?",
                (rs, n) -> readRun(rs), userId, clientRequestId);
        if (runs.isEmpty()) throw new BusinessException(4040, "未找到该请求的执行记录");
        var run = runs.getFirst();
        new AgentStepReconciler(jdbc).reconcile(userId, run.runId(), clientRequestId);
        jdbc.update("UPDATE agent_step_recovery_attempts SET status='UNKNOWN',outcome='RECOVERY_INTERRUPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND status='RUNNING' AND created_at<DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 MINUTE)", run.runId());
        var steps = jdbc.query("""
                SELECT *, (recovery_started_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)) AS recovering
                FROM agent_run_steps WHERE run_id=? ORDER BY sequence_no
                """, (rs, n) -> readStep(rs, run), run.runId());
        return new AgentRunResponse(run.runId(), run.clientRequestId(), run.conversationId(), run.userMessage(), run.status(),
                run.errorCode(), run.createdAt(), run.finishedAt(), run.response(), steps);
    }

    private AgentRunResponse.Step readStep(ResultSet rs, AgentRunResponse run) throws SQLException {
        int sequence = rs.getInt("sequence_no");
        String tool = rs.getString("tool_name");
        String arguments = rs.getString("arguments_json");
        String status = rs.getString("status");
        if (!"RUNNING".equals(run.status()) && "RUNNING".equals(status)) status = "UNKNOWN";
        String outcome = rs.getString("outcome");
        int retries = rs.getInt("retry_count");
        boolean recovering = rs.getBoolean("recovering") && !"SUCCEEDED".equals(status);
        boolean canRetry = !"RUNNING".equals(run.status()) && !"SUCCEEDED".equals(status) && !recovering
                && retries < AgentRecoveryService.MAX_RETRIES && AgentRecoveryService.eligible(tool, outcome);
        if (canRetry) canRetry = Integer.valueOf(0).equals(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run_steps WHERE run_id=? AND sequence_no<? AND status<>'SUCCEEDED'", Integer.class, run.runId(), sequence));
        String description = "使用保存的原参数重新查询，不重新调用模型";
        if ("CartTool".equals(tool)) {
            try {
                var request = com.example.ai.tool.support.CartOperationIdentity.fromArguments(run.clientRequestId(), arguments);
                if (!"get".equals(request.operation())) {
                    String action = switch (request.operation()) { case "add" -> "加购"; case "update" -> "修改数量"; default -> "移除"; };
                    description = action + "商品 " + request.productId() + (request.quantity() == null ? "" : "，数量 " + request.quantity())
                            + "。按原参数重试；如果原操作已成功，只取回结果。";
                }
            } catch (BusinessException invalid) { canRetry = false; description = "保存的参数无效，请人工核对"; }
        }
        String resultJson = rs.getString("result_json");
        String summary = null;
        if (resultJson != null) {
            try { summary = JSON.readTree(resultJson).path("message").asText(null); }
            catch (Exception ignored) { /* 损坏结果保留，不生成事实。 */ }
        }
        var attempts = jdbc.query("SELECT * FROM agent_step_recovery_attempts WHERE run_id=? AND sequence_no=? ORDER BY attempt_no",
                (row, n) -> new AgentRunResponse.RecoveryAttempt(row.getInt("attempt_no"), row.getString("status"),
                        row.getString("outcome"), row.getTimestamp("created_at").toLocalDateTime()), run.runId(), sequence);
        return new AgentRunResponse.Step(sequence, tool, arguments, status, outcome, resultJson, rs.getString("result_source"),
                retries, recovering, canRetry, description, summary, attempts);
    }

    @Override
    public List<AgentRunResponse> recent(Long userId, String conversationId) {
        return jdbc.queryForList("SELECT client_request_id FROM agent_runs WHERE user_id=? AND conversation_id=? ORDER BY created_at DESC LIMIT 20",
                String.class, userId, conversationId).stream().map(id -> find(userId, id)).toList();
    }

    private AgentRunResponse readRun(ResultSet rs) throws SQLException {
        var finished = rs.getTimestamp("finished_at");
        return new AgentRunResponse(rs.getString("run_id"), rs.getString("client_request_id"), rs.getString("conversation_id"),
                rs.getString("user_message"), rs.getString("status"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toLocalDateTime(), finished == null ? null : finished.toLocalDateTime(),
                decode(rs.getString("response_json")), List.of());
    }

    private static AiChatResponse decode(String value) {
        if (value == null) return null;
        try { return JSON.readValue(value, AiChatResponse.class); }
        catch (Exception e) { throw new IllegalStateException("无法读取已保存的任务结果", e); }
    }

    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("无法保存任务结果", e); }
    }

    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
