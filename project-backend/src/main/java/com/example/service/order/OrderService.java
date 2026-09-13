package com.example.service.order;

import com.example.dto.order.Response.OrderResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dto.order.Request.OrderQueryRequest;
import com.example.dto.order.Response.OrderSummaryResponse;

/** 当前登录用户的模拟订单服务。 */
public interface OrderService {
    /** 从当前用户购物车创建订单，并按幂等键复用已创建订单。 */
    OrderResponse create(Long userId, String idempotencyKey);

    /** 确认当前用户指定订单预览，并在事务内重新校验价格与库存。 */
    OrderResponse confirmDraft(Long userId, String draftId, String idempotencyKey);

    /** 分页查询当前用户订单。 */
    Page<OrderSummaryResponse> page(Long userId, OrderQueryRequest request);

    /** 查询当前用户订单详情，订单不存在或不属于当前用户时统一返回不存在。 */
    OrderResponse findDetail(Long userId, Long orderId);

    /**
     * 支付当前用户的待支付订单；重复支付返回既有订单结果，不重复迁移状态。
     * 订单已关闭或已超时时抛出冲突异常。
     */
    OrderResponse pay(Long userId, Long orderId);

    /**
     * 关闭当前用户的待支付订单并归还库存；重复取消返回既有订单结果，不重复归还库存。
     * 订单已支付时抛出冲突异常。
     */
    OrderResponse cancel(Long userId, Long orderId);
}
