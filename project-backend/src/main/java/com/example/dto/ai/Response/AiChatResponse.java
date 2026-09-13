package com.example.dto.ai.Response;

import com.example.dto.order.Response.OrderDraftResponse;

import java.util.List;

/** AI 聊天接口返回体；购物编排状态只能来自服务端实际操作结果。 */
public record AiChatResponse(
        AiChatResultStatus resultStatus,
        String conversationId,
        String message,
        List<AiToolCallResponse> toolCalls,
        List<AiReferenceResponse> references,
        List<AiKnowledgeReferenceResponse> knowledgeReferences,
        String status,
        OrderDraftResponse orderDraft) {
    public AiChatResponse(String conversationId,
                          String message,
                          List<AiToolCallResponse> toolCalls,
                          List<AiReferenceResponse> references) {
        this(AiChatResultStatus.SUCCESS, conversationId, message, toolCalls, references, List.of(), null, null);
    }

    public AiChatResponse(String conversationId,
                          String message,
                          List<AiToolCallResponse> toolCalls,
                          List<AiReferenceResponse> references,
                          List<AiKnowledgeReferenceResponse> knowledgeReferences) {
        this(AiChatResultStatus.SUCCESS, conversationId, message, toolCalls, references, knowledgeReferences, null, null);
    }

    public AiChatResponse {
        resultStatus = resultStatus == null ? AiChatResultStatus.SUCCESS : resultStatus;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        references = references == null ? List.of() : List.copyOf(references);
        knowledgeReferences = knowledgeReferences == null ? List.of() : List.copyOf(knowledgeReferences);
    }

    public boolean successful() {
        return resultStatus == AiChatResultStatus.SUCCESS;
    }
}
