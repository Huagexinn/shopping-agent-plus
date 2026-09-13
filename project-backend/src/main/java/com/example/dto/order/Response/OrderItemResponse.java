package com.example.dto.order.Response;

import java.math.BigDecimal;

/** 订单明细响应，金额字段均为下单时保存的服务端快照。 */
public record OrderItemResponse(
        Long productId,
        String productTitle,
        String brand,
        String model,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal) {
}
