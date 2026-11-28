package com.example.dto.ai.Response;

import java.time.LocalDateTime;
import java.util.List;

/** 仅对任务所属用户开放；UNKNOWN 表示无法证明业务是否已提交，不能据此自动重试。 */
public record AgentRunResponse(String runId, String clientRequestId, String conversationId,
                               String userMessage, String status, String errorCode,
                               LocalDateTime createdAt, LocalDateTime finishedAt,
                               AiChatResponse response, List<Step> steps) {
    public record Step(int sequence, String toolName, String arguments, String status,
                       String outcome, String resultJson, String resultSource, int retryCount,
                       boolean recovering, boolean canRetry, String recoveryDescription,
                       String resultSummary, List<RecoveryAttempt> recoveryAttempts) {}
    public record RecoveryAttempt(int attempt, String status, String outcome, LocalDateTime createdAt) {}
}
