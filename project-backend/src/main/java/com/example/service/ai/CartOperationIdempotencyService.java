package com.example.service.ai;

import com.example.ai.model.response.CartToolResult;

import java.util.function.Supplier;

/** 购物车 Tool 写操作的持久化幂等边界，Redis 仅缓存已提交结果。 */
public interface CartOperationIdempotencyService {
    /**
     * 按 userId + operationId 唯一识别操作；相同指纹返回首次成功结果，不同指纹拒绝执行。
     * 操作记录与购物车修改必须在同一数据库事务提交；Redis 不可用时回查数据库。
     */
    CartToolResult execute(Long userId, String operationId, String fingerprint,
                           Supplier<CartToolResult> operation);
}
