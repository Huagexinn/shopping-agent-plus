package com.example.service.ai;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AgentRunResponse;
import java.util.List;

public interface AgentRunService {
    record Start(String runId, AiChatResponse replay) {}
    Start begin(Long userId, AiChatRequest request);
    void finish(Long userId, String runId, AiChatResponse response);
    void interrupt(Long userId, String runId, String reason);
    void heartbeat(Long userId, String runId);
    void startStep(Long userId, String runId, int sequence, String toolName, String arguments);
    void finishStep(Long userId, String runId, int sequence, String outcome, Object result);
    AgentRunResponse find(Long userId, String clientRequestId);
    List<AgentRunResponse> recent(Long userId, String conversationId);
}
