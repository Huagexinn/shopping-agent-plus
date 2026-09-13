package com.example.service.order.impl;

import com.example.dto.cart.Response.CartItemResponse;
import com.example.dto.cart.Response.CartResponse;
import com.example.dto.order.Response.OrderDraftResponse;
import com.example.dto.order.Response.OrderPreviewItemResponse;
import com.example.entity.ProductEntity;
import com.example.exception.BusinessException;
import com.example.service.cart.CartService;
import com.example.service.order.OrderDraft;
import com.example.service.order.OrderDraftService;
import com.example.service.product.ProductService;
import com.example.utils.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 基于 Redis 的订单预览草稿服务；Redis 不可用时默认拒绝预览。 */
@Service
public class RedisOrderDraftService implements OrderDraftService {
    private static final Logger log = LoggerFactory.getLogger(RedisOrderDraftService.class);
    private static final String KEY_PREFIX = "shopping:v1:order:draft:";
    private static final String PREVIEWED = "PREVIEWED";
    private static final String SOURCE_CART = "CART";
    private static final int PROTOCOL_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Resource
    private CartService cartService;
    @Resource
    private ProductService productService;
    @Resource
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    @Value("${app.order.draft-ttl:10m}")
    private Duration draftTtl = Duration.ofMinutes(10);

    @Override
    @Transactional(readOnly = true)
    public OrderDraftResponse preview(Long userId) {
        Long currentUserId = requireUserId(userId);
        StringRedisTemplate redis = requireRedis();
        CartResponse cart = cartService.getCart(currentUserId);
        if (cart.items().isEmpty()) {
            throw new BusinessException("购物车为空，无法创建订单预览");
        }

        List<OrderPreviewItemResponse> items = new ArrayList<>(cart.items().size());
        BigDecimal total = BigDecimal.ZERO;
        for (CartItemResponse cartItem : cart.items()) {
            if (cartItem.quantity() == null || cartItem.quantity() <= 0) {
                throw new BusinessException("购物车商品数量无效");
            }
            ProductEntity product = productService.findById(cartItem.productId());
            if (product == null) {
                throw new BusinessException("商品不存在，无法创建订单预览");
            }
            if (product.getStatus() == null || product.getStatus() != Constants.ON_SALE) {
                throw new BusinessException("商品已下架，无法创建订单预览");
            }
            if (product.getPrice() == null) {
                throw new BusinessException("商品价格无效，无法创建订单预览");
            }
            if (product.getStock() == null || product.getStock() < cartItem.quantity()) {
                throw new BusinessException("商品库存不足，无法创建订单预览");
            }
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.quantity()));
            total = total.add(subtotal);
            items.add(new OrderPreviewItemResponse(product.getId(), product.getTitle(), product.getBrand(),
                    product.getModel(), product.getPrice(), cartItem.quantity(), subtotal));
        }

        LocalDateTime createdAt = LocalDateTime.now();
        Duration ttl = draftTtl == null || draftTtl.isNegative() || draftTtl.isZero()
                ? Duration.ofMinutes(10) : draftTtl;
        LocalDateTime expiresAt = createdAt.plus(ttl);
        String draftId = UUID.randomUUID().toString();
        DraftPayload payload = new DraftPayload(draftId, currentUserId, createdAt, expiresAt, PREVIEWED,
                items, total, SOURCE_CART, PROTOCOL_VERSION);
        try {
            redis.opsForValue().set(key(currentUserId, draftId), MAPPER.writeValueAsString(payload), ttl);
        } catch (Exception exception) {
            log.warn("Redis订单预览写入失败，拒绝创建订单预览", exception);
            throw unavailable();
        }
        return toResponse(payload);
    }

    @Override
    public OrderDraft get(Long userId, String draftId) {
        Long currentUserId = requireUserId(userId);
        validateDraftId(draftId);
        StringRedisTemplate redis = requireRedis();
        String value;
        try {
            value = redis.opsForValue().get(key(currentUserId, draftId));
        } catch (RuntimeException exception) {
            log.warn("Redis订单预览读取失败，拒绝确认订单", exception);
            throw unavailable();
        }
        if (value == null) {
            throw new BusinessException("订单预览不存在或已过期");
        }
        try {
            DraftPayload payload = MAPPER.readValue(value, DraftPayload.class);
            if (payload == null || !currentUserId.equals(payload.userId())
                    || !draftId.equals(payload.draftId()) || payload.expiresAt() == null
                    || !payload.expiresAt().isAfter(LocalDateTime.now())
                    || !PREVIEWED.equals(payload.status())) {
                throw new BusinessException("订单预览不存在或已过期");
            }
            return new OrderDraft(payload.draftId(), payload.userId(), payload.createdAt(), payload.expiresAt(),
                    payload.status(), payload.items(), payload.previewTotalAmount(), payload.source(), payload.version());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Redis订单预览格式无效，拒绝确认订单", exception);
            throw unavailable();
        }
    }

    @Override
    public void delete(Long userId, String draftId) {
        if (userId == null || userId <= 0 || draftId == null || draftId.isBlank()) {
            return;
        }
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) {
            log.warn("Redis订单预览删除失败，Redis不可用");
            return;
        }
        try {
            redis.delete(key(userId, draftId));
        } catch (RuntimeException exception) {
            log.warn("Redis订单预览删除失败，等待草稿自然过期", exception);
        }
    }

    private OrderDraftResponse toResponse(DraftPayload payload) {
        return new OrderDraftResponse(payload.draftId(), payload.createdAt(), payload.expiresAt(), payload.status(),
                payload.items(), payload.previewTotalAmount(), payload.source(), payload.version());
    }

    private String key(Long userId, String draftId) {
        return KEY_PREFIX + userId + ":" + draftId;
    }

    private StringRedisTemplate requireRedis() {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null || redis.getConnectionFactory() == null) {
            throw unavailable();
        }
        try {
            redis.execute((RedisCallback<String>) connection -> connection.ping());
        } catch (RuntimeException exception) {
            log.warn("Redis订单预览连通性检查失败，拒绝订单预览或确认", exception);
            throw unavailable();
        }
        return redis;
    }

    private StringRedisTemplate redisTemplate() {
        return redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("未登录或登录已失效");
        }
        return userId;
    }

    private void validateDraftId(String draftId) {
        if (draftId == null || !draftId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new BusinessException("订单预览ID格式不正确");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException("订单预览暂不可用，请稍后重试");
    }

    private record DraftPayload(
            String draftId,
            Long userId,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            String status,
            List<OrderPreviewItemResponse> items,
            BigDecimal previewTotalAmount,
            String source,
            int version) {
        private DraftPayload {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
