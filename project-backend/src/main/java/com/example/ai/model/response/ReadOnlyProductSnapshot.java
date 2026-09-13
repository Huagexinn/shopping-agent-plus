package com.example.ai.model.response;

import java.math.BigDecimal;
import java.util.List;

/** 只读 Tool 输出的商品事实快照。 */
public record ReadOnlyProductSnapshot(
        Long productId,
        String brand,
        String model,
        String title,
        String description,
        BigDecimal price,
        Integer stock,
        Integer status,
        Integer ramGb,
        Integer storageGb,
        boolean onSale,
        boolean specsComplete,
        boolean matched,
        boolean available,
        Integer requiredQuantity,
        Integer rank,
        String reason,
        List<String> matchedFacts,
        List<String> missingFacts) {
    public ReadOnlyProductSnapshot {
        matchedFacts = matchedFacts == null ? List.of() : List.copyOf(matchedFacts);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }
}
