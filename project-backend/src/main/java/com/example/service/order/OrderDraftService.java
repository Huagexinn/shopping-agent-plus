package com.example.service.order;

import com.example.dto.order.Response.OrderDraftResponse;

/** 订单预览草稿的生成、读取和生命周期管理。 */
public interface OrderDraftService {
    /** 从当前用户购物车生成不扣库存的订单预览。 */
    OrderDraftResponse preview(Long userId);

    /** 读取当前用户草稿；不存在或过期时抛出稳定业务异常。 */
    OrderDraft get(Long userId, String draftId);

    /** 在订单事务提交后删除草稿；Redis 失败只记录告警。 */
    void delete(Long userId, String draftId);
}
