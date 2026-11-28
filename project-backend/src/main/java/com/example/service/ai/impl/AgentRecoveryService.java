package com.example.service.ai.impl;

import com.example.ai.model.response.CartToolResult;
import com.example.ai.tool.support.CartOperationIdentity;
import com.example.dto.ai.Response.AgentRunResponse;
import com.example.exception.BusinessException;
import com.example.exception.ConflictException;
import com.example.service.ai.AgentRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Objects;

/** 恢复资格、原参数、次数和执行权均由服务器决定。网络重复点击不消耗额外并发执行权。 */
@Service
public class AgentRecoveryService {
    static final int MAX_RETRIES = 2;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AgentRunService runs;
    private final FrozenToolExecutor executor;

    public AgentRecoveryService(JdbcTemplate jdbc, PlatformTransactionManager manager, AgentRunService runs, FrozenToolExecutor executor) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.executor = executor;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(10);
    }

    public AgentRunResponse recover(Long user, String client, int sequence, boolean confirmed) {
        // 首先查询归属、处理过期任务并对账；成功的业务不会重做。
        AgentRunResponse run = runs.find(user, client);
        Ticket ticket = transaction.execute(tx -> {
            String state = jdbc.queryForObject("SELECT status FROM agent_runs WHERE user_id=? AND run_id=? FOR UPDATE", String.class, user, run.runId());
            if ("RUNNING".equals(state)) throw new ConflictException("原任务仍在执行，请先等待执行结果");
            var rows = jdbc.queryForList("""
                    SELECT *, (recovery_started_at > DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 2 MINUTE)) AS recovering
                    FROM agent_run_steps WHERE run_id=? AND sequence_no=? FOR UPDATE
                    """, run.runId(), sequence);
            if (rows.isEmpty()) throw new BusinessException("执行步骤不存在");
            var step = rows.getFirst();
            if ("SUCCEEDED".equals(step.get("status"))) return null;
            Integer pendingBefore = jdbc.queryForObject("SELECT COUNT(*) FROM agent_run_steps WHERE run_id=? AND sequence_no<? AND status<>'SUCCEEDED'", Integer.class, run.runId(), sequence);
            if (pendingBefore != null && pendingBefore > 0) throw new ConflictException("请先核对或恢复前面的未完成步骤");
            String tool = (String) step.get("tool_name");
            String outcome = (String) step.get("outcome");
            if (!eligible(tool, outcome)) throw new ConflictException("该步骤不支持恢复，或业务条件不满足，请核对后重新操作");
            if (Boolean.TRUE.equals(step.get("recovering")) || (step.get("recovering") instanceof Number n && n.intValue() != 0)) throw new ConflictException("该步骤正在恢复，请查询结果");
            int retries = ((Number) step.get("retry_count")).intValue();
            if (retries >= MAX_RETRIES) throw new ConflictException("恢复次数已达上限，请人工核对");
            String arguments = (String) step.get("arguments_json");
            if ("CartTool".equals(tool)) {
                var request = CartOperationIdentity.fromArguments(client, arguments);
                if (!"get".equals(request.operation()) && !confirmed) throw new ConflictException("重试购物车修改前，必须确认原操作内容");
            }
            // 超时恢复的旧执行可能仍在途。购物车仍由业务幂等唯一键仲裁，回调按 attempt_no 隔离。
            jdbc.update("UPDATE agent_step_recovery_attempts SET status='UNKNOWN',outcome='RECOVERY_INTERRUPTED',finished_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND sequence_no=? AND status='RUNNING'", run.runId(), sequence);
            jdbc.update("UPDATE agent_run_steps SET retry_count=retry_count+1,recovery_started_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND sequence_no=?", run.runId(), sequence);
            jdbc.update("INSERT INTO agent_step_recovery_attempts(run_id,sequence_no,attempt_no,status) VALUES (?,?,?,'RUNNING')", run.runId(), sequence, retries + 1);
            return new Ticket(run.runId(), sequence, retries + 1, tool, arguments);
        });
        if (ticket == null) return runs.find(user, client);
        Object result = null;
        String outcome;
        try {
            List<Object> facts = jdbc.query("SELECT tool_name,result_json FROM agent_run_steps WHERE run_id=? AND sequence_no<? AND status='SUCCEEDED'",
                    (rs, n) -> NativeToolAudit.parseToolResult(rs.getString(1), rs.getString(2)), ticket.run(), sequence)
                    .stream().filter(Objects::nonNull).toList();
            result = executor.execute(user, client, ticket.tool(), ticket.arguments(), facts);
            outcome = NativeToolAudit.resultOutcome(result);
        } catch (BusinessException business) {
            outcome = "BUSINESS_FAILURE";
            result = java.util.Map.of("message", business.getMessage() == null ? "业务条件不满足" : business.getMessage());
        } catch (RuntimeException transientFailure) {
            outcome = "SYSTEM_FAILURE";
        }
        String status = stepState(outcome, result);
        String json = encode(result);
        String savedOutcome = outcome;
        transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE agent_step_recovery_attempts SET status=?,outcome=?,finished_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND sequence_no=? AND attempt_no=?", status, savedOutcome, ticket.run(), sequence, ticket.attempt());
            // 新恢复轮次、业务对账成功均不能被旧执行失败覆盖。
            jdbc.update("""
                    UPDATE agent_run_steps SET status=?,outcome=?,result_json=?,result_source='RECOVERY',
                        recovery_started_at=NULL,finished_at=CURRENT_TIMESTAMP(6)
                    WHERE run_id=? AND sequence_no=? AND retry_count=? AND status<>'SUCCEEDED'
                    """, status, savedOutcome, json, ticket.run(), sequence, ticket.attempt());
        });
        return runs.find(user, client);
    }

    static boolean eligible(String tool, String outcome) {
        return FrozenToolExecutor.allowed(tool) && !"BUSINESS_FAILURE".equals(outcome)
                && !"PARAMETER_CONFLICT".equals(outcome) && !"POLICY_REJECTED".equals(outcome);
    }

    static String stepState(String outcome, Object result) {
        if ("SUCCESS".equals(outcome) || "PARTIAL_SUCCESS".equals(outcome)) return "SUCCEEDED";
        if ("BUSINESS_FAILURE".equals(outcome)) return "FAILED";
        if (result instanceof CartToolResult || result == null) return "UNKNOWN";
        return "FAILED";
    }

    private static String encode(Object value) {
        if (value == null) return null;
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("恢复结果无法保存，请查询业务结果", e); }
    }
    private record Ticket(String run, int sequence, int attempt, String tool, String arguments) {}
}
