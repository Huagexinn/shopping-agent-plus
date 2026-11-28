package com.example.ai.model.response;

import com.example.dto.cart.Response.CartResponse;

/** 购物车 Tool 的结构化执行结果。 */
public record CartToolResult(
        String toolName,
        boolean success,
        String operation,
        String message,
        CartResponse cart,
        Long changedProductId,
        String failureType) {
    public CartToolResult(String toolName, boolean success, String operation, String message,
                          CartResponse cart, Long changedProductId) {
        this(toolName, success, operation, message, cart, changedProductId, success ? null : "UNKNOWN");
    }
}
