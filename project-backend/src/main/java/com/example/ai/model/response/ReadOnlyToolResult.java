package com.example.ai.model.response;

import java.time.LocalDateTime;
import java.util.List;

/** 只读 Tool 的统一执行结果。 */
public record ReadOnlyToolResult(
        String toolName,
        boolean success,
        String message,
        List<ReadOnlyProductSnapshot> products,
        List<Long> productIds,
        long total,
        LocalDateTime queriedAt,
        ReadOnlyToolResultStatus resultStatus) {
    public ReadOnlyToolResult(String toolName,
                              boolean success,
                              String message,
                              List<ReadOnlyProductSnapshot> products,
                              List<Long> productIds,
                              long total,
                              LocalDateTime queriedAt) {
        this(toolName, success, message, products, productIds, total, queriedAt,
                success ? ReadOnlyToolResultStatus.SUCCESS : ReadOnlyToolResultStatus.BUSINESS_FAILURE);
    }

    public ReadOnlyToolResult {
        products = products == null ? List.of() : List.copyOf(products);
        productIds = productIds == null ? List.of() : List.copyOf(productIds);
        resultStatus = resultStatus == null
                ? (success ? ReadOnlyToolResultStatus.SUCCESS : ReadOnlyToolResultStatus.BUSINESS_FAILURE)
                : resultStatus;
    }

    public boolean hasUsableFacts() {
        return resultStatus == ReadOnlyToolResultStatus.SUCCESS
                || resultStatus == ReadOnlyToolResultStatus.PARTIAL_SUCCESS;
    }
}
