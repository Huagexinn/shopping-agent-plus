package com.example.service.ai;

import com.example.ai.model.response.CartToolResult;

import java.util.function.Supplier;

/** 购物车 Tool 写操作的 Redis 短期去重边界。 */
public interface CartOperationIdempotencyService {
    /**
     * 在 Redis 可用且操作未被处理过时执行一次写操作；重复操作返回首次结果。
     * 操作本身仍由 CartService 负责事务和商品事实校验。
     */
    CartToolResult execute(Long userId, String operationId, String fingerprint,
                           Supplier<CartToolResult> operation);
}
