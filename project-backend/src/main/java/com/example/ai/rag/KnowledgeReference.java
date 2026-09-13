package com.example.ai.rag;

import java.time.LocalDateTime;

/** 注入 AI 上下文的可追溯知识片段。 */
public record KnowledgeReference(
        String documentId,
        String title,
        String content,
        String sourceType,
        Long productId,
        double score,
        String version,
        LocalDateTime updatedAt) {
}
