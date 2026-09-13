package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.CartItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 购物车明细数据访问接口。 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {
    /** 在订单事务中锁定当前购物车的有效明细，固定本次结算范围。 */
    @Select("""
            SELECT id, cart_id AS cartId, product_id AS productId,
                   quantity, display_price AS displayPrice,
                   created_at AS createdAt, updated_at AS updatedAt, deleted
            FROM cart_items
            WHERE cart_id = #{cartId} AND deleted = 0
            ORDER BY product_id ASC, id ASC
            FOR UPDATE
            """)
    List<CartItemEntity> selectActiveByCartIdForUpdate(@Param("cartId") Long cartId);
}
