package com.example.dto.cart.Response;

import java.math.BigDecimal;
import java.util.List;

/** 当前用户购物车展示对象。 */
public record CartResponse(
        Long id,
        List<CartItemResponse> items,
        Integer totalQuantity,
        BigDecimal displayTotalAmount) {
}
