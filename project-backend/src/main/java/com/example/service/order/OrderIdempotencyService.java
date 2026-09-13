package com.example.service.order;

/** 订单幂等结果缓存和短期处理锁。 */
public interface OrderIdempotencyService {
    /** Redis 操作结果，available=false 表示 Redis 不可用，应由数据库流程兜底。 */
    record LockResult(boolean available, boolean acquired, String ownerToken) {
    }

    /** 查询当前用户幂等请求已缓存的订单 ID。 */
    Long getResult(Long userId, String idempotencyKey);

    /** 判断 Redis 是否可用；订单确认在 Redis 不可用时默认拒绝。 */
    boolean isAvailable();

    /** 查询幂等键已绑定的订单草稿 ID。 */
    String getDraftId(Long userId, String idempotencyKey);

    /** 绑定幂等键和订单草稿，防止同一幂等键确认不同草稿。 */
    void bindDraft(Long userId, String idempotencyKey, String draftId);

    /** 尝试获取当前用户幂等请求的短期处理锁。 */
    LockResult tryAcquire(Long userId, String idempotencyKey);

    /** 事务提交后写入订单结果索引。 */
    void saveResult(Long userId, String idempotencyKey, Long orderId);

    /** 仅在 owner token 匹配时释放处理锁。 */
    void release(Long userId, String idempotencyKey, String ownerToken);
}
