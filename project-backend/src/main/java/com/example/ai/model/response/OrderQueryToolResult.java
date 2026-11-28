package com.example.ai.model.response;

import com.example.dto.order.Response.OrderResponse;
import com.example.dto.order.Response.OrderSummaryResponse;

import java.util.List;

/** 订单查询 Tool 的结构化执行结果。 */
public record OrderQueryToolResult(
        String toolName,
        boolean success,
        String operation,
        String message,
        List<OrderSummaryResponse> orders,
        OrderResponse order,
        long page,
        long size,
        long total,
        String failureType) {
    public OrderQueryToolResult(String toolName, boolean success, String operation, String message,
                                List<OrderSummaryResponse> orders, OrderResponse order, long page, long size, long total) {
        this(toolName, success, operation, message, orders, order, page, size, total, success ? null : "BUSINESS_FAILURE");
    }
    public OrderQueryToolResult {
        orders = orders == null ? List.of() : List.copyOf(orders);
    }
}
