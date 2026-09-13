package com.example.dto.order.Response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情响应。
 * expiresAt 为支付时限；paidAt 仅已支付订单有值；closedAt 和 closeReason 仅已关闭订单有值，
 * closeReason 取值为 USER_CANCELLED 或 PAYMENT_TIMEOUT。
 */
public record OrderResponse(
        Long id,
        String orderNo,
        Integer status,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime closedAt,
        String closeReason,
        List<OrderItemResponse> items) {
}
