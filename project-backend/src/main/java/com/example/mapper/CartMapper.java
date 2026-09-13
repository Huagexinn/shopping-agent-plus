package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.CartEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 购物车主表数据访问接口。 */
@Mapper
public interface CartMapper extends BaseMapper<CartEntity> {
    /** 按用户锁定购物车主行，保证订单结算期间购物车不会被并发写入。 */
    @Select("""
            SELECT id, user_id AS userId, created_at AS createdAt,
                   updated_at AS updatedAt, deleted
            FROM carts
            WHERE user_id = #{userId} AND deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    CartEntity selectByUserIdForUpdate(@Param("userId") Long userId);
}
