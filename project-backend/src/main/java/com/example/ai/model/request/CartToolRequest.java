package com.example.ai.model.request;

/** 购物车 Tool 的白名单操作参数。 */
public record CartToolRequest(
        String operation,
        Long productId,
        Integer quantity,
        String operationId) {
}
