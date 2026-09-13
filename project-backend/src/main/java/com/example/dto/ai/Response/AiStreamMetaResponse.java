package com.example.dto.ai.Response;

/** 流式响应起始事件：标识本轮会话与追踪 ID。 */
public record AiStreamMetaResponse(String conversationId, String aiTraceId, String reqId) {
}
