package com.example.service.ai.impl;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AgentRunResponse;
import com.example.service.ai.AgentRunService;
import java.util.List;
import java.util.UUID;

/** 仅供请求格式与底层购物车幂等测试：显式绕过任务入口去重，以覆盖业务层防线。 */
class UnrecordedTestRuns implements AgentRunService {
    public Start begin(Long user, AiChatRequest request) { return new Start(UUID.randomUUID().toString(), null); }
    public void finish(Long user, String run, AiChatResponse response) {}
    public void interrupt(Long user, String run, String reason) {}
    public void heartbeat(Long user, String run) {}
    public void startStep(Long user, String run, int sequence, String tool, String arguments) {}
    public void finishStep(Long user, String run, int sequence, String outcome, Object result) {}
    public AgentRunResponse find(Long user, String request) { throw new UnsupportedOperationException(); }
    public List<AgentRunResponse> recent(Long user, String conversation) { throw new UnsupportedOperationException(); }
}
