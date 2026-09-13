package com.example.dto.ai.Response;

/** 只读 AI 回复中的知识文档引用。 */
public record AiKnowledgeReferenceResponse(
        String documentId,
        String title,
        String sourceType,
        String content,
        Long productId) {
}
