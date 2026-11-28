package com.example.service.order;

import com.example.dto.order.Response.OrderDraftResponse;

/** 订单预览草稿的生成、读取和生命周期管理。 */
public interface OrderDraftService {
    /** 从当前用户购物车生成不扣库存的订单预览。 */
    OrderDraftResponse preview(Long userId);

    /** 查看同一份预览及其确认结果，不创建新草稿。 */
    OrderDraftResponse detail(Long userId, String draftId);

    /** 读取当前用户草稿；不存在或过期时抛出稳定业务异常。 */
    OrderDraft get(Long userId, String draftId);

    /** 取消尚未确认的草稿，保留历史；已确认草稿及其订单绑定不会被删除。 */
    void delete(Long userId, String draftId);
}
