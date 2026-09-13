package com.example.controller;

import com.example.common.ApiResponse;
import com.example.common.Const;
import com.example.dto.cart.Request.AddCartItemRequest;
import com.example.dto.cart.Request.UpdateCartItemRequest;
import com.example.dto.cart.Response.CartResponse;
import com.example.exception.UnauthorizedException;
import com.example.service.cart.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 登录用户购物车接口。 */
@RestController
@RequestMapping("/api/cart")
@Tag(name = "购物车", description = "登录用户购物车读写接口")
@Validated
public class CartController {
    @Resource
    private CartService cartService;

    @GetMapping
    @Operation(summary = "查询当前用户购物车",
            description = "返回当前登录用户的购物车明细和仅用于展示的金额信息，不接受用户 ID 参数。")
    public ApiResponse<CartResponse> getCart(@RequestAttribute(value = Const.ATTR_USER_ID, required = false)
                                                 Long userId) {
        return ApiResponse.success(cartService.getCart(requireUserId(userId)));
    }

    @PostMapping("/items")
    @Operation(summary = "添加购物车商品",
            description = "校验商品上架状态和实时库存后添加商品；重复添加同一商品会累加数量，价格由服务端读取。")
    public ApiResponse<CartResponse> addItem(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Valid @RequestBody AddCartItemRequest request) {
        return ApiResponse.success(cartService.addItem(requireUserId(userId), request));
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "修改购物车商品数量",
            description = "按当前登录用户修改指定商品数量，并重新校验商品状态、库存和当前展示价格。")
    public ApiResponse<CartResponse> updateItem(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "商品业务 ID，必须为正整数", example = "1")
            @PathVariable @Positive(message = "商品ID必须是正整数") Long productId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return ApiResponse.success(cartService.updateItem(requireUserId(userId),
                requireProductId(productId), request));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "删除购物车商品",
            description = "逻辑删除当前登录用户购物车中的指定商品，并返回删除后的购物车。")
    public ApiResponse<CartResponse> removeItem(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "商品业务 ID，必须为正整数", example = "1")
            @PathVariable @Positive(message = "商品ID必须是正整数") Long productId) {
        return ApiResponse.success(cartService.removeItem(requireUserId(userId),
                requireProductId(productId)));
    }

    @DeleteMapping
    @Operation(summary = "清空当前用户购物车",
            description = "逻辑删除当前登录用户购物车中的全部有效明细；空购物车重复调用保持幂等。")
    public ApiResponse<CartResponse> clear(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId) {
        return ApiResponse.success(cartService.clear(requireUserId(userId)));
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("未登录或登录已失效");
        }
        return userId;
    }

    private Long requireProductId(Long productId) {
        if (productId == null || productId <= 0) {
            throw new com.example.exception.BusinessException("商品ID必须是正整数");
        }
        return productId;
    }
}
