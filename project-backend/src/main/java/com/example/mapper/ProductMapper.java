package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.ProductEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** 手机商品数据访问接口。 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductEntity> {
    @Select("SELECT * FROM products WHERE id=#{productId} AND deleted=0 FOR UPDATE")
    ProductEntity selectByIdForUpdate(@Param("productId") Long productId);
    /** 按实时库存条件扣减商品库存，返回 1 才表示扣减成功；非法数量不会执行更新。 */
    @Update("""
            UPDATE products
            SET stock = stock - #{quantity}, updated_at = #{updatedAt}
            WHERE id = #{productId}
              AND deleted = 0
              AND status = 1
              AND #{quantity} > 0
              AND stock >= #{quantity}
            """)
    int decrementStock(@Param("productId") Long productId,
                       @Param("quantity") Integer quantity,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 归还商品库存，用于订单取消和超时关单，返回 1 才表示归还成功。
     * 与扣减不同，这里不能附加 status = 1：商品可能在订单待支付期间被下架，
     * 附加该条件会导致库存无法归还且不产生任何报错。
     */
    @Update("""
            UPDATE products
            SET stock = stock + #{quantity}, updated_at = #{updatedAt}
            WHERE id = #{productId}
              AND deleted = 0
              AND #{quantity} > 0
            """)
    int restoreStock(@Param("productId") Long productId,
                     @Param("quantity") Integer quantity,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
