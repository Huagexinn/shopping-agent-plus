package com.example.dto.ai.Response;

import java.util.List;

/** 只读 AI 回复中的商品引用。 */
public record AiReferenceResponse(
        Long productId,
        String title,
        String source,
        List<String> facts) {
    public AiReferenceResponse {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
