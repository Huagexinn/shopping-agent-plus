package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 模拟订单主表数据访问接口。 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {
    /** 按当前用户和幂等键查询已有订单。 */
    @Select("""
            SELECT id, order_no AS orderNo, user_id AS userId, total_amount AS totalAmount,
                   expires_at AS expiresAt, paid_at AS paidAt, closed_at AS closedAt,
                   close_reason AS closeReason, status,
                   idempotency_key AS idempotencyKey, created_at AS createdAt,
                   updated_at AS updatedAt, deleted
            FROM orders
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} AND deleted = 0
            LIMIT 1
            """)
    OrderEntity selectByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                                 @Param("idempotencyKey") String idempotencyKey);

    /** 在订单事务中以当前读再次查询幂等订单，避免并发请求等待锁后读取旧快照。 */
    @Select("""
            SELECT id, order_no AS orderNo, user_id AS userId, total_amount AS totalAmount,
                   expires_at AS expiresAt, paid_at AS paidAt, closed_at AS closedAt,
                   close_reason AS closeReason, status,
                   idempotency_key AS idempotencyKey, created_at AS createdAt,
                   updated_at AS updatedAt, deleted
            FROM orders
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} AND deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    OrderEntity selectByUserIdAndIdempotencyKeyForUpdate(@Param("userId") Long userId,
                                                          @Param("idempotencyKey") String idempotencyKey);

    /** 按订单 ID 和当前用户查询订单主记录，直接执行归属边界。 */
    @Select("""
            SELECT id, order_no AS orderNo, user_id AS userId, total_amount AS totalAmount,
                   expires_at AS expiresAt, paid_at AS paidAt, closed_at AS closedAt,
                   close_reason AS closeReason, status,
                   idempotency_key AS idempotencyKey, created_at AS createdAt,
                   updated_at AS updatedAt, deleted
            FROM orders
            WHERE id = #{orderId} AND user_id = #{userId} AND deleted = 0
            LIMIT 1
            """)
    OrderEntity selectByIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /**
     * 以当前读查询当前用户的订单，用于条件更新未命中后判断订单真实状态。
     * 必须使用当前读，否则会读到事务开始时的快照，把并发中已变更的订单误判为待支付。
     */
    @Select("""
            SELECT id, order_no AS orderNo, user_id AS userId, total_amount AS totalAmount,
                   expires_at AS expiresAt, paid_at AS paidAt, closed_at AS closedAt,
                   close_reason AS closeReason, status,
                   idempotency_key AS idempotencyKey, created_at AS createdAt,
                   updated_at AS updatedAt, deleted
            FROM orders
            WHERE id = #{orderId} AND user_id = #{userId} AND deleted = 0
            LIMIT 1
            FOR UPDATE
            """)
    OrderEntity selectByIdAndUserIdForUpdate(@Param("orderId") Long orderId, @Param("userId") Long userId);

    /** 分页查询当前用户订单，按创建时间和订单 ID 倒序排列。 */
    @Select("""
            SELECT id, order_no AS orderNo, user_id AS userId, total_amount AS totalAmount,
                   expires_at AS expiresAt, paid_at AS paidAt, closed_at AS closedAt,
                   close_reason AS closeReason, status,
                   idempotency_key AS idempotencyKey, created_at AS createdAt,
                   updated_at AS updatedAt, deleted
            FROM orders
            WHERE user_id = #{userId} AND deleted = 0
            ORDER BY created_at DESC, id DESC
            """)
    IPage<OrderEntity> selectPageByUserId(IPage<OrderEntity> page, @Param("userId") Long userId);

    /**
     * 将待支付且未超时的订单迁移为已支付（status = 2）。
     * status 前置条件是并发控制：affected = 0 表示请求重复、订单已关闭或已超时。
     */
    @Update("""
            UPDATE orders
            SET status = 2, paid_at = #{paidAt}, updated_at = #{paidAt}
            WHERE id = #{orderId} AND user_id = #{userId}
              AND status = 1 AND expires_at > #{paidAt} AND deleted = 0
            """)
    int markPaid(@Param("orderId") Long orderId,
                 @Param("userId") Long userId,
                 @Param("paidAt") LocalDateTime paidAt);

    /**
     * 将待支付订单迁移为已关闭（status = 3）。
     * status 前置条件保证已支付订单不会被关闭：affected = 0 表示请求重复或订单已支付。
     */
    @Update("""
            UPDATE orders
            SET status = 3, closed_at = #{closedAt}, close_reason = #{closeReason}, updated_at = #{closedAt}
            WHERE id = #{orderId} AND user_id = #{userId}
              AND status = 1 AND deleted = 0
            """)
    int markClosed(@Param("orderId") Long orderId,
                   @Param("userId") Long userId,
                   @Param("closedAt") LocalDateTime closedAt,
                   @Param("closeReason") String closeReason);

    /**
     * 将已超时且仍待支付的订单迁移为已关闭（status = 3，close_reason = PAYMENT_TIMEOUT）。
     * 条件同时校验 status = 1 与 expires_at &lt;= now，与 markPaid 的超时判断互斥，
     * 因此关单任务与用户支付不可能同时成功。
     */
    @Update("""
            UPDATE orders
            SET status = 3, closed_at = #{now}, close_reason = 'PAYMENT_TIMEOUT', updated_at = #{now}
            WHERE id = #{orderId} AND status = 1 AND expires_at <= #{now} AND deleted = 0
            """)
    int markExpiredClosed(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    /** 扫描已超时但仍待支付的订单 ID，按超时时间升序返回，供关单任务分批处理。 */
    @Select("""
            SELECT id
            FROM orders
            WHERE status = 1 AND expires_at <= #{now} AND deleted = 0
            ORDER BY expires_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> selectExpiredPendingOrderIds(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
