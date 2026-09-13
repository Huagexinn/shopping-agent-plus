package com.example.service.cart;

import com.example.dto.cart.Request.AddCartItemRequest;
import com.example.dto.cart.Request.UpdateCartItemRequest;
import com.example.dto.cart.Response.CartResponse;

/** 登录用户购物车业务服务。 */
public interface CartService {
    CartResponse getCart(Long userId);

    CartResponse addItem(Long userId, AddCartItemRequest request);

    CartResponse updateItem(Long userId, Long productId, UpdateCartItemRequest request);

    CartResponse removeItem(Long userId, Long productId);

    CartResponse clear(Long userId);
}
