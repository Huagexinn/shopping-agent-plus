package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.OrderItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 模拟订单明细数据访问接口。 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {
    /** 查询订单下的有效明细数量。 */
    @Select("""
            SELECT COUNT(*)
            FROM order_items
            WHERE order_id = #{orderId} AND deleted = 0
            """)
    long countByOrderId(@Param("orderId") Long orderId);

    /** 查询订单下的有效明细，保留明细快照字段。 */
    @Select("""
            SELECT id, order_id AS orderId, product_id AS productId,
                   product_title AS productTitle, brand, model,
                   unit_price AS unitPrice, quantity, subtotal,
                   created_at AS createdAt, updated_at AS updatedAt, deleted
            FROM order_items
            WHERE order_id = #{orderId} AND deleted = 0
            ORDER BY id ASC
            """)
    List<OrderItemEntity> selectByOrderId(@Param("orderId") Long orderId);
}
