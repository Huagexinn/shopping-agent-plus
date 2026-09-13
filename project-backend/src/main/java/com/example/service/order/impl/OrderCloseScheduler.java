package com.example.service.order.impl;

import com.example.entity.OrderItemEntity;
import com.example.exception.BusinessException;
import com.example.mapper.OrderItemMapper;
import com.example.mapper.OrderMapper;
import com.example.mapper.ProductMapper;
import com.example.service.product.ProductService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 超时订单关单任务：关闭已超过支付时限但仍待支付的订单，并归还下单时占用的库存。
 * 关单使用条件更新，与用户支付互斥，因此不会与支付重复处理同一订单。
 */
@Component
public class OrderCloseScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderCloseScheduler.class);
    /** 单轮最多处理的订单数，避免一次扫描长期占用连接和行锁。 */
    private static final int MAX_BATCH_SIZE = 100;

    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;
    @Resource
    private ProductMapper productMapper;
    @Resource
    private ProductService productService;
    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 扫描并关闭超时订单。
     * 每个订单使用独立事务：单个订单归还库存失败只回滚该订单，不会阻塞同批次的其他订单。
     */
    @Scheduled(fixedDelayString = "${app.order.close-scan-delay:1m}")
    public void closeExpiredOrders() {
        List<Long> orderIds;
        try {
            orderIds = orderMapper.selectExpiredPendingOrderIds(LocalDateTime.now(), MAX_BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn("超时订单扫描失败，等待下轮重试", exception);
            return;
        }
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        int closed = 0;
        for (Long orderId : orderIds) {
            try {
                CloseOutcome outcome = template.execute(status -> closeExpiredOrder(orderId));
                if (outcome == null || !outcome.closed()) {
                    continue;
                }
                outcome.productIds().forEach(productService::evictDetailCache);
                closed++;
            } catch (RuntimeException exception) {
                log.warn("超时订单关闭失败，订单保持待支付等待下轮重试 orderId={}", orderId, exception);
            }
        }
        if (closed > 0) {
            log.info("超时订单关单完成 scanned={} closed={}", orderIds.size(), closed);
        }
    }

    /**
     * 在单个订单事务内关闭超时订单并归还库存。
     * 未抢到状态迁移时返回 closed=false，表示订单已被支付或已被其他实例关闭，本次不做任何变更。
     */
    private CloseOutcome closeExpiredOrder(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        if (orderMapper.markExpiredClosed(orderId, now) != 1) {
            return CloseOutcome.skipped();
        }
        List<OrderItemEntity> orderItems = orderItemMapper.selectByOrderId(orderId);
        for (OrderItemEntity orderItem : orderItems) {
            if (productMapper.restoreStock(orderItem.getProductId(), orderItem.getQuantity(), now) != 1) {
                throw new BusinessException("库存归还失败，订单未关闭");
            }
        }
        return new CloseOutcome(true, orderItems.stream()
                .map(OrderItemEntity::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    }

    /** 单次关单结果；productIds 用于在事务提交后失效商品详情缓存。 */
    private record CloseOutcome(boolean closed, List<Long> productIds) {
        private CloseOutcome {
            productIds = productIds == null ? List.of() : List.copyOf(productIds);
        }

        private static CloseOutcome skipped() {
            return new CloseOutcome(false, List.of());
        }
    }
}
