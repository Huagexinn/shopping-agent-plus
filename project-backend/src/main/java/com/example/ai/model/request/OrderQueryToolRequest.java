package com.example.ai.model.request;

/** 订单查询 Tool 的白名单查询参数。 */
public record OrderQueryToolRequest(
        String operation,
        Long orderId,
        Long page,
        Long size) {
}
