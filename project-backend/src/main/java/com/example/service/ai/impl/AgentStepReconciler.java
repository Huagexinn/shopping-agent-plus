package com.example.service.ai.impl;

import com.example.ai.model.response.CartToolResult;
import com.example.ai.tool.support.CartOperationIdentity;
import com.example.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只读取已提交的业务记录；不存在记录不代表业务未执行，绝不据此宣称回滚。 */
final class AgentStepReconciler {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final JdbcTemplate jdbc;
    AgentStepReconciler(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void reconcile(Long user, String run, String client) {
        var steps = jdbc.queryForList("SELECT sequence_no,arguments_json FROM agent_run_steps WHERE run_id=? AND tool_name='CartTool' AND status<>'SUCCEEDED'", run);
        for (var step : steps) {
            com.example.ai.model.request.CartToolRequest request;
            try { request = CartOperationIdentity.fromArguments(client, (String) step.get("arguments_json")); }
            catch (BusinessException invalid) { continue; }
            if (request.operationId() == null) continue;
            var records = jdbc.queryForList("SELECT fingerprint,status,result_json FROM cart_operation_records WHERE user_id=? AND operation_id=?", user, request.operationId());
            if (records.isEmpty()) continue;
            var record = records.getFirst();
            if (!CartOperationIdentity.fingerprint(request.operation(), request.productId(), request.quantity()).equals(record.get("fingerprint"))) {
                jdbc.update("UPDATE agent_run_steps SET status='FAILED',outcome='PARAMETER_CONFLICT',result_source='BUSINESS_RECORD',finished_at=CURRENT_TIMESTAMP(6) WHERE run_id=? AND sequence_no=? AND status<>'SUCCEEDED'", run, step.get("sequence_no"));
                continue;
            }
            if (!"SUCCEEDED".equals(record.get("status")) || record.get("result_json") == null) continue;
            String json = record.get("result_json").toString();
            try {
                var result = JSON.readValue(json, CartToolResult.class);
                if (!result.success() || !request.operation().equals(result.operation()) || !request.productId().equals(result.changedProductId())) continue;
            } catch (Exception corrupted) { continue; }
            jdbc.update("""
                    UPDATE agent_run_steps SET status='SUCCEEDED',outcome='SUCCESS',result_json=?,result_source='BUSINESS_RECORD',
                        finished_at=CURRENT_TIMESTAMP(6)
                    WHERE run_id=? AND sequence_no=? AND status<>'SUCCEEDED'
                    """, json, run, step.get("sequence_no"));
        }
    }
}
