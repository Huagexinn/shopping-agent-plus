package com.example.dto.cart.Response;

import java.math.BigDecimal;

/** 购物车明细展示对象。 */
public record CartItemResponse(
        Long productId,
        String brand,
        String model,
        String title,
        BigDecimal displayPrice,
        Integer stock,
        Integer quantity,
        Integer status,
        BigDecimal displaySubtotal) {
}
