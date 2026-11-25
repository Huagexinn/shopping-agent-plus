
package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.CartOperationRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CartOperationRecordMapper
        extends BaseMapper<CartOperationRecordEntity> {

    /**
     * 根据用户ID和操作ID查询历史操作记录。
     */
    @Select("""
            SELECT
                id,
                user_id AS userId,
                operation_id AS operationId,
                fingerprint,
                status,
                result_json AS resultJson,
                created_at AS createdAt,
                updated_at AS updatedAt
            FROM cart_operation_records
            WHERE user_id = #{userId}
              AND operation_id = #{operationId}
            LIMIT 1
            """)
    CartOperationRecordEntity selectByUserIdAndOperationId(
            @Param("userId") Long userId,
            @Param("operationId") String operationId
    );
}
