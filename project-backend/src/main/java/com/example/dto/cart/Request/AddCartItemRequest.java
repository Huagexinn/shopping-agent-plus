package com.example.dto.cart.Request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 添加购物车商品请求。 */
@Data
public class AddCartItemRequest {
    @NotNull(message = "商品ID不能为空")
    @Positive(message = "商品ID必须是正整数")
    private Long productId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于0")
    @Max(value = 999, message = "数量不能超过999")
    private Integer quantity;
}
