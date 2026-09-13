package com.example.service.order.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dto.order.Request.OrderQueryRequest;
import com.example.dto.order.Response.OrderItemResponse;
import com.example.dto.order.Response.OrderResponse;
import com.example.dto.order.Response.OrderSummaryResponse;
import com.example.dto.order.Response.OrderPreviewItemResponse;
import com.example.entity.CartEntity;
import com.example.entity.CartItemEntity;
import com.example.entity.OrderEntity;
import com.example.entity.OrderItemEntity;
import com.example.entity.OrderStatus;
import com.example.entity.ProductEntity;
import com.example.exception.BusinessException;
import com.example.exception.ConflictException;
import com.example.exception.ResourceNotFoundException;
import com.example.exception.UnauthorizedException;
import com.example.mapper.CartItemMapper;
import com.example.mapper.CartMapper;
import com.example.mapper.OrderItemMapper;
import com.example.mapper.OrderMapper;
import com.example.mapper.ProductMapper;
import com.example.service.order.OrderIdempotencyService;
import com.example.service.order.OrderDraft;
import com.example.service.order.OrderDraftService;
import com.example.service.order.OrderService;
import com.example.service.product.ProductService;
import com.example.utils.Constants;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 当前登录用户的模拟订单业务实现。 */
@Service
public class OrderServiceImpl implements OrderService {
    private static final DateTimeFormatter ORDER_NO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private CartMapper cartMapper;
    @Resource
    private CartItemMapper cartItemMapper;
    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderItemMapper orderItemMapper;
    @Resource
    private ProductMapper productMapper;
    @Resource
    private ProductService productService;
    @Resource
    private OrderIdempotencyService orderIdempotencyService;
    @Resource
    private OrderDraftService orderDraftService;

    /** 订单支付时限，订单创建时写入 expires_at；非法配置回退为 30 分钟。 */
    @Value("${app.order.payment-timeout:30m}")
    private Duration paymentTimeout = Duration.ofMinutes(30);

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse create(Long userId, String idempotencyKey) {
        Long currentUserId = validateUserId(userId);
        String normalizedKey = validateIdempotencyKey(idempotencyKey);

        Long cachedOrderId = orderIdempotencyService == null
                ? null : orderIdempotencyService.getResult(currentUserId, normalizedKey);
        if (cachedOrderId != null) {
            OrderResponse cachedResponse = findOrderResponse(currentUserId, cachedOrderId);
            if (cachedResponse != null) {
                return cachedResponse;
            }
        }

        OrderIdempotencyService.LockResult lockResult = orderIdempotencyService == null
                ? new OrderIdempotencyService.LockResult(false, false, null)
                : orderIdempotencyService.tryAcquire(currentUserId, normalizedKey);
        if (lockResult == null) {
            lockResult = new OrderIdempotencyService.LockResult(false, false, null);
        }
        if (lockResult.available() && !lockResult.acquired()) {
            OrderEntity processingOrder =
                    orderMapper.selectByUserIdAndIdempotencyKey(currentUserId, normalizedKey);
            if (processingOrder != null) {
                return toResponse(processingOrder, orderItemMapper.selectByOrderId(processingOrder.getId()));
            }
            throw new ConflictException("订单正在处理中，请稍后重试");
        }
        ProcessingContext processingContext = lockResult.acquired()
                ? registerProcessing(lockResult.ownerToken(), currentUserId, normalizedKey) : null;

        OrderEntity existing = orderMapper.selectByUserIdAndIdempotencyKey(currentUserId, normalizedKey);
        if (existing != null) {
            completeProcessing(processingContext, existing.getId());
            return toResponse(existing, orderItemMapper.selectByOrderId(existing.getId()));
        }

        CartEntity cart = cartMapper.selectByUserIdForUpdate(currentUserId);
        if (cart == null) {
            throw new BusinessException("购物车为空，无法创建订单");
        }
        existing = orderMapper.selectByUserIdAndIdempotencyKeyForUpdate(currentUserId, normalizedKey);
        if (existing != null) {
            return toResponse(existing, orderItemMapper.selectByOrderId(existing.getId()));
        }
        List<CartItemEntity> cartItems = cartItemMapper.selectActiveByCartIdForUpdate(cart.getId());
        if (cartItems.isEmpty()) {
            throw new BusinessException("购物车为空，无法创建订单");
        }
        List<CartItemEntity> settlementItems = cartItems.stream()
                .sorted(Comparator.comparing(CartItemEntity::getProductId))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        List<OrderItemEntity> orderItems = new ArrayList<>(settlementItems.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItemEntity cartItem : settlementItems) {
            if (cartItem.getQuantity() == null || cartItem.getQuantity() <= 0) {
                throw new BusinessException("购物车商品数量无效");
            }
            ProductEntity product = productService.findById(cartItem.getProductId());
            if (product == null) {
                throw new BusinessException("商品不存在，无法创建订单");
            }
            if (product.getStatus() == null || product.getStatus() != Constants.ON_SALE) {
                throw new BusinessException("商品已下架，无法创建订单");
            }
            if (product.getPrice() == null) {
                throw new BusinessException("商品价格无效，无法创建订单");
            }
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
            totalAmount = totalAmount.add(subtotal);
            orderItems.add(new OrderItemEntity(null, null, product.getId(), product.getTitle(),
                    product.getBrand(), product.getModel(), product.getPrice(), cartItem.getQuantity(),
                    subtotal, now, now, 0));
        }

        for (OrderItemEntity orderItem : orderItems) {
            int affected =
                    productMapper.decrementStock(orderItem.getProductId(), orderItem.getQuantity(), now);
            if (affected != 1) {
                throw new BusinessException("商品库存不足，无法创建订单");
            }
        }
        evictProductDetailCacheAfterCommit(orderItems);

        OrderEntity order = new OrderEntity(null, generateOrderNo(now), currentUserId, totalAmount,
                paymentExpiresAt(now), null, null, null, OrderStatus.PENDING_PAYMENT.getCode(),
                normalizedKey, now, now, 0);
        try {
            if (orderMapper.insert(order) != 1) {
                throw new BusinessException("订单创建失败");
            }
        } catch (DuplicateKeyException exception) {
            OrderEntity concurrentOrder =
                    orderMapper.selectByUserIdAndIdempotencyKey(currentUserId, normalizedKey);
            if (concurrentOrder != null) {
                completeProcessing(processingContext, concurrentOrder.getId());
                return toResponse(concurrentOrder, orderItemMapper.selectByOrderId(concurrentOrder.getId()));
            }
            throw new ConflictException("订单正在处理中，请稍后重试");
        }

        for (OrderItemEntity orderItem : orderItems) {
            orderItem.setOrderId(order.getId());
            if (orderItemMapper.insert(orderItem) != 1) {
                throw new BusinessException("订单明细写入失败");
            }
        }

        int cleared = cartItemMapper.update(null, Wrappers.<CartItemEntity>lambdaUpdate()
                .set(CartItemEntity::getDeleted, 1)
                .set(CartItemEntity::getUpdatedAt, now)
                .eq(CartItemEntity::getCartId, cart.getId())
                .in(CartItemEntity::getId, settlementItems.stream().map(CartItemEntity::getId).toList())
                .eq(CartItemEntity::getDeleted, 0));
        if (cleared != settlementItems.size()) {
            throw new BusinessException("购物车清理失败，订单未创建");
        }
        completeProcessing(processingContext, order.getId());
        return toResponse(order, orderItems);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse confirmDraft(Long userId, String draftId, String idempotencyKey) {
        Long currentUserId = validateUserId(userId);
        String normalizedKey = validateIdempotencyKey(idempotencyKey);
        validateDraftId(draftId);
        requireConfirmInfrastructure();

        String boundDraftId = orderIdempotencyService.getDraftId(currentUserId, normalizedKey);
        if (boundDraftId != null && !boundDraftId.equals(draftId)) {
            throw new ConflictException("同一幂等键不能确认不同订单预览");
        }
        Long cachedOrderId = orderIdempotencyService.getResult(currentUserId, normalizedKey);
        if (cachedOrderId != null) {
            OrderResponse cachedResponse = findOrderResponse(currentUserId, cachedOrderId);
            if (cachedResponse != null) {
                return cachedResponse;
            }
        }

        OrderDraft draft = orderDraftService.get(currentUserId, draftId);
        orderIdempotencyService.bindDraft(currentUserId, normalizedKey, draftId);
        OrderIdempotencyService.LockResult lockResult = orderIdempotencyService
                .tryAcquire(currentUserId, normalizedKey);
        if (lockResult == null || !lockResult.available()) {
            throw new BusinessException("订单确认暂不可用，请稍后重试");
        }
        if (!lockResult.acquired()) {
            OrderEntity processingOrder = orderMapper
                    .selectByUserIdAndIdempotencyKey(currentUserId, normalizedKey);
            if (processingOrder != null) {
                return toResponse(processingOrder, orderItemMapper.selectByOrderId(processingOrder.getId()));
            }
            throw new ConflictException("订单正在处理中，请稍后重试");
        }

        ProcessingContext processingContext = registerProcessing(lockResult.ownerToken(), currentUserId,
                normalizedKey, draftId);
        OrderEntity existing = orderMapper.selectByUserIdAndIdempotencyKeyForUpdate(currentUserId, normalizedKey);
        if (existing != null) {
            completeProcessing(processingContext, existing.getId());
            return toResponse(existing, orderItemMapper.selectByOrderId(existing.getId()));
        }

        CartEntity cart = cartMapper.selectByUserIdForUpdate(currentUserId);
        if (cart == null) {
            throw new BusinessException("购物车为空，无法确认订单");
        }
        List<CartItemEntity> activeCartItems = cartItemMapper.selectActiveByCartIdForUpdate(cart.getId());
        Map<Long, CartItemEntity> cartItemsByProduct = new HashMap<>();
        for (CartItemEntity item : activeCartItems) {
            cartItemsByProduct.put(item.getProductId(), item);
        }
        if (draft.items() == null || draft.items().isEmpty()
                || draft.items().stream().anyMatch(item -> item == null || item.productId() == null)) {
            throw new BusinessException("订单预览为空，无法确认订单");
        }
        List<OrderPreviewItemResponse> draftItems = draft.items().stream()
                .sorted(Comparator.comparing(OrderPreviewItemResponse::productId))
                .toList();
        if (activeCartItems.size() != draftItems.size() || cartItemsByProduct.size() != activeCartItems.size()) {
            throw new BusinessException("购物车已变化，请重新创建订单预览");
        }
        Set<Long> draftProductIds = new HashSet<>();
        for (OrderPreviewItemResponse draftItem : draftItems) {
            if (!draftProductIds.add(draftItem.productId())) {
                throw new BusinessException("订单预览包含重复商品");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<OrderItemEntity> orderItems = new ArrayList<>(draftItems.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderPreviewItemResponse draftItem : draftItems) {
            if (draftItem == null || draftItem.productId() == null || draftItem.productId() <= 0
                    || draftItem.quantity() == null || draftItem.quantity() <= 0) {
                throw new BusinessException("订单预览商品数量无效");
            }
            CartItemEntity cartItem = cartItemsByProduct.get(draftItem.productId());
            if (cartItem == null || !draftItem.quantity().equals(cartItem.getQuantity())) {
                throw new BusinessException("购物车已变化，请重新创建订单预览");
            }
            ProductEntity product = productService.findById(draftItem.productId());
            if (product == null) {
                throw new BusinessException("商品不存在，无法确认订单");
            }
            if (product.getStatus() == null || product.getStatus() != Constants.ON_SALE) {
                throw new BusinessException("商品已下架，无法确认订单");
            }
            if (product.getPrice() == null) {
                throw new BusinessException("商品价格无效，无法确认订单");
            }
            if (draftItem.previewUnitPrice() == null
                    || product.getPrice().compareTo(draftItem.previewUnitPrice()) != 0) {
                throw new BusinessException("商品价格已变化，请重新创建订单预览");
            }
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(draftItem.quantity()));
            totalAmount = totalAmount.add(subtotal);
            orderItems.add(new OrderItemEntity(null, null, product.getId(), product.getTitle(),
                    product.getBrand(), product.getModel(), product.getPrice(), draftItem.quantity(),
                    subtotal, now, now, 0));
        }

        for (OrderItemEntity orderItem : orderItems) {
            int affected = productMapper.decrementStock(orderItem.getProductId(), orderItem.getQuantity(), now);
            if (affected != 1) {
                throw new BusinessException("商品库存不足，无法确认订单");
            }
        }
        evictProductDetailCacheAfterCommit(orderItems);

        OrderEntity order = new OrderEntity(null, generateOrderNo(now), currentUserId, totalAmount,
                paymentExpiresAt(now), null, null, null, OrderStatus.PENDING_PAYMENT.getCode(),
                normalizedKey, now, now, 0);
        try {
            if (orderMapper.insert(order) != 1) {
                throw new BusinessException("订单创建失败");
            }
        } catch (DuplicateKeyException exception) {
            OrderEntity concurrentOrder = orderMapper
                    .selectByUserIdAndIdempotencyKey(currentUserId, normalizedKey);
            if (concurrentOrder != null) {
                completeProcessing(processingContext, concurrentOrder.getId());
                return toResponse(concurrentOrder, orderItemMapper.selectByOrderId(concurrentOrder.getId()));
            }
            throw new ConflictException("订单正在处理中，请稍后重试");
        }

        for (OrderItemEntity orderItem : orderItems) {
            orderItem.setOrderId(order.getId());
            if (orderItemMapper.insert(orderItem) != 1) {
                throw new BusinessException("订单明细写入失败");
            }
        }

        List<Long> cartItemIds = draftItems.stream()
                .map(OrderPreviewItemResponse::productId)
                .map(cartItemsByProduct::get)
                .map(CartItemEntity::getId)
                .toList();
        int cleared = cartItemMapper.update(null, Wrappers.<CartItemEntity>lambdaUpdate()
                .set(CartItemEntity::getDeleted, 1)
                .set(CartItemEntity::getUpdatedAt, now)
                .eq(CartItemEntity::getCartId, cart.getId())
                .in(CartItemEntity::getId, cartItemIds)
                .eq(CartItemEntity::getDeleted, 0));
        if (cleared != cartItemIds.size()) {
            throw new BusinessException("购物车清理失败，订单未创建");
        }
        completeProcessing(processingContext, order.getId());
        return toResponse(order, orderItems);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> page(Long userId, OrderQueryRequest request) {
        Long currentUserId = validateUserId(userId);
        OrderQueryRequest normalized = request == null ? new OrderQueryRequest() : request;
        if (normalized.getPage() < 1) {
            throw new BusinessException("页码必须大于0");
        }
        if (normalized.getSize() < 1 || normalized.getSize() > Constants.MAX_PAGE_SIZE) {
            throw new BusinessException("每页数量必须在1到" + Constants.MAX_PAGE_SIZE + "之间");
        }

        Page<OrderEntity> requestedPage = new Page<>(normalized.getPage(), normalized.getSize());
        IPage<OrderEntity> entityPage = orderMapper.selectPageByUserId(requestedPage, currentUserId);
        if (entityPage == null) {
            entityPage = requestedPage;
        }
        Page<OrderSummaryResponse> responsePage = new Page<>(
                entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        responsePage.setRecords(entityPage.getRecords().stream()
                .map(order -> new OrderSummaryResponse(order.getId(), order.getOrderNo(), order.getStatus(),
                        order.getTotalAmount(), order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(),
                        order.getClosedAt(), order.getCloseReason(),
                        Math.toIntExact(orderItemMapper.countByOrderId(order.getId()))))
                .toList());
        return responsePage;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse findDetail(Long userId, Long orderId) {
        Long currentUserId = validateUserId(userId);
        Long currentOrderId = validateOrderId(orderId);
        OrderEntity order = orderMapper.selectByIdAndUserId(currentOrderId, currentUserId);
        if (order == null) {
            throw new ResourceNotFoundException("订单不存在");
        }
        return toResponse(order, orderItemMapper.selectByOrderId(order.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse pay(Long userId, Long orderId) {
        Long currentUserId = validateUserId(userId);
        Long currentOrderId = validateOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.markPaid(currentOrderId, currentUserId, now);
        OrderEntity order = orderMapper.selectByIdAndUserIdForUpdate(currentOrderId, currentUserId);
        if (order == null) {
            throw new ResourceNotFoundException("订单不存在");
        }
        if (affected != 1) {
            return reuseOrRejectPaidOrder(order);
        }
        return toResponse(order, orderItemMapper.selectByOrderId(order.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse cancel(Long userId, Long orderId) {
        Long currentUserId = validateUserId(userId);
        Long currentOrderId = validateOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();
        int affected = orderMapper.markClosed(currentOrderId, currentUserId, now,
                OrderStatus.CLOSE_REASON_USER_CANCELLED);
        OrderEntity order = orderMapper.selectByIdAndUserIdForUpdate(currentOrderId, currentUserId);
        if (order == null) {
            throw new ResourceNotFoundException("订单不存在");
        }
        if (affected != 1) {
            return reuseOrRejectCancelledOrder(order);
        }
        List<OrderItemEntity> orderItems = orderItemMapper.selectByOrderId(order.getId());
        for (OrderItemEntity orderItem : orderItems) {
            if (productMapper.restoreStock(orderItem.getProductId(), orderItem.getQuantity(), now) != 1) {
                throw new BusinessException("库存归还失败，订单未取消");
            }
        }
        evictProductDetailCacheAfterCommit(orderItems);
        return toResponse(order, orderItems);
    }

    /**
     * 支付的条件更新未命中时按订单真实状态给出结果。
     * 重复支付返回既有订单保证接口幂等；待支付但未命中只可能是已超过支付时限。
     */
    private OrderResponse reuseOrRejectPaidOrder(OrderEntity order) {
        OrderStatus status = OrderStatus.fromCode(order.getStatus());
        if (status == OrderStatus.PAID) {
            return toResponse(order, orderItemMapper.selectByOrderId(order.getId()));
        }
        if (status == OrderStatus.CLOSED) {
            throw new ConflictException("订单已关闭，无法支付");
        }
        if (status == OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException("订单已超时，无法支付");
        }
        throw new BusinessException("订单状态异常，无法支付");
    }

    /** 取消的条件更新未命中时按订单真实状态给出结果；重复取消返回既有订单且不重复归还库存。 */
    private OrderResponse reuseOrRejectCancelledOrder(OrderEntity order) {
        OrderStatus status = OrderStatus.fromCode(order.getStatus());
        if (status == OrderStatus.CLOSED) {
            return toResponse(order, orderItemMapper.selectByOrderId(order.getId()));
        }
        if (status == OrderStatus.PAID) {
            throw new ConflictException("订单已支付，无法取消");
        }
        throw new BusinessException("订单状态异常，无法取消");
    }

    private OrderResponse findOrderResponse(Long userId, Long orderId) {
        OrderEntity order = orderMapper.selectByIdAndUserId(orderId, userId);
        return order == null ? null : toResponse(order, orderItemMapper.selectByOrderId(order.getId()));
    }

    private ProcessingContext registerProcessing(String ownerToken, Long userId, String idempotencyKey) {
        return registerProcessing(ownerToken, userId, idempotencyKey, null);
    }

    private ProcessingContext registerProcessing(String ownerToken, Long userId, String idempotencyKey,
                                                 String draftId) {
        ProcessingContext context = new ProcessingContext(userId, idempotencyKey, ownerToken, draftId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return context;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (context.orderId != null) {
                    orderIdempotencyService.saveResult(context.userId, context.idempotencyKey, context.orderId);
                }
                if (context.draftId != null) {
                    orderDraftService.delete(context.userId, context.draftId);
                }
                releaseProcessing(context);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    releaseProcessing(context);
                }
            }
        });
        return context;
    }

    private void completeProcessing(ProcessingContext context, Long orderId) {
        if (context == null) {
            return;
        }
        context.orderId = orderId;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            orderIdempotencyService.saveResult(context.userId, context.idempotencyKey, orderId);
            if (context.draftId != null) {
                orderDraftService.delete(context.userId, context.draftId);
            }
            releaseProcessing(context);
        }
    }

    private void releaseProcessing(ProcessingContext context) {
        if (context.released) {
            return;
        }
        context.released = true;
        orderIdempotencyService.release(context.userId, context.idempotencyKey, context.ownerToken);
    }

    private static final class ProcessingContext {
        private final Long userId;
        private final String idempotencyKey;
        private final String ownerToken;
        private final String draftId;
        private Long orderId;
        private boolean released;

        private ProcessingContext(Long userId, String idempotencyKey, String ownerToken, String draftId) {
            this.userId = userId;
            this.idempotencyKey = idempotencyKey;
            this.ownerToken = ownerToken;
            this.draftId = draftId;
        }
    }

    private String generateOrderNo(LocalDateTime now) {
        return "SO" + ORDER_NO_TIME_FORMAT.format(now)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private OrderResponse toResponse(OrderEntity order, List<OrderItemEntity> items) {
        List<OrderItemResponse> responses = items.stream()
                .map(item -> new OrderItemResponse(item.getProductId(),
                        item.getProductTitle(), item.getBrand(),
                        item.getModel(), item.getUnitPrice(),
                        item.getQuantity(), item.getSubtotal()))
                .toList();
        return new OrderResponse(order.getId(), order.getOrderNo(),
                order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(),
                order.getClosedAt(), order.getCloseReason(), responses);
    }

    private Long validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("未登录或登录已失效");
        }
        return userId;
    }

    private Long validateOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new BusinessException("订单ID必须是正整数");
        }
        return orderId;
    }

    private LocalDateTime paymentExpiresAt(LocalDateTime now) {
        Duration timeout = paymentTimeout == null || paymentTimeout.isNegative() || paymentTimeout.isZero()
                ? Duration.ofMinutes(30) : paymentTimeout;
        return now.plus(timeout);
    }

    /**
     * 在事务提交后失效受影响商品的详情缓存。
     * 必须在提交后执行：提交前删除会被并发读回填为旧值；失效失败不影响已提交的库存变更。
     */
    private void evictProductDetailCacheAfterCommit(List<OrderItemEntity> orderItems) {
        List<Long> productIds = orderItems == null ? List.of() : orderItems.stream()
                .map(OrderItemEntity::getProductId)
                .filter(productId -> productId != null && productId > 0)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictProductDetailCache(productIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictProductDetailCache(productIds);
            }
        });
    }

    private void evictProductDetailCache(List<Long> productIds) {
        for (Long productId : productIds) {
            productService.evictDetailCache(productId);
        }
    }

    private String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64
                || idempotencyKey.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("Idempotency-Key必须是1到64个可打印字符");
        }
        return idempotencyKey;
    }

    private void validateDraftId(String draftId) {
        if (draftId == null || !draftId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new BusinessException("订单预览ID格式不正确");
        }
    }

    private void requireConfirmInfrastructure() {
        if (orderIdempotencyService == null || orderDraftService == null
                || !orderIdempotencyService.isAvailable()) {
            throw new BusinessException("订单确认暂不可用，请稍后重试");
        }
    }
}
