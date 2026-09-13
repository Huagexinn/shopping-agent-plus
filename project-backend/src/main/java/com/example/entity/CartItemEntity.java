package com.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 购物车明细持久化实体。 */
@TableName("cart_items")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CartItemEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long cartId;
    private Long productId;
    private Integer quantity;
    private BigDecimal displayPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
}
