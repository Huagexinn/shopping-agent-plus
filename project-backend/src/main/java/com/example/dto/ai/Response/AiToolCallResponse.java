package com.example.dto.ai.Response;

import java.time.LocalDateTime;
import java.util.List;

/** 只读 AI 聊天中的单次 Tool 调用记录。 */
public record AiToolCallResponse(
        String toolName,
        boolean success,
        String resultSummary,
        List<Long> productIds,
        long total,
        LocalDateTime queriedAt,
        String arguments,
        int sequence,
        String evidence,
        String resultStatus) {
    public AiToolCallResponse(String toolName,
                              boolean success,
                              String resultSummary,
                              List<Long> productIds,
                              long total,
                              LocalDateTime queriedAt,
                              String arguments,
                              int sequence,
                              String evidence) {
        this(toolName, success, resultSummary, productIds, total, queriedAt, arguments, sequence, evidence, null);
    }

    public AiToolCallResponse {
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        arguments = arguments == null ? null : arguments.trim();
        evidence = evidence == null || evidence.isBlank() ? "MODEL_REQUEST" : evidence;
        resultStatus = resultStatus == null || resultStatus.isBlank()
                ? (success ? "SUCCESS" : "BUSINESS_FAILURE") : resultStatus;
    }
}
