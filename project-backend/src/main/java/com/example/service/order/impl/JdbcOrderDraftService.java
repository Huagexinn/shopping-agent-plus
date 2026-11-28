package com.example.service.order.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dto.order.Response.*;
import com.example.entity.*;
import com.example.exception.*;
import com.example.mapper.*;
import com.example.service.order.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** 持久化不可变订单预览；生成预览不扣库存，查看旧预览不自动生成新草稿。 */
@Service
public class JdbcOrderDraftService implements OrderDraftService {
    private final OrderDraftStore store;
    private final CartMapper carts;
    private final CartItemMapper items;
    private final ProductMapper products;
    @Value("${app.order.draft-ttl:10m}") private Duration ttl = Duration.ofMinutes(10);
    public JdbcOrderDraftService(OrderDraftStore store, CartMapper carts, CartItemMapper items, ProductMapper products) {
        this.store = store; this.carts = carts; this.items = items; this.products = products;
    }

    @Override
    @Transactional(timeout = 30, rollbackFor = Exception.class)
    public OrderDraftResponse preview(Long user) {
        requireUser(user);
        var cart = carts.selectByUserIdForUpdate(user);
        if (cart == null) throw new BusinessException("购物车为空，无法预览");
        var cartItems = items.selectActiveByCartIdForUpdate(cart.getId()).stream().sorted(Comparator.comparing(CartItemEntity::getProductId)).toList();
        if (cartItems.isEmpty()) throw new BusinessException("购物车为空，无法预览");
        List<OrderPreviewItemResponse> snapshot = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var item : cartItems) {
            var product = products.selectByIdForUpdate(item.getProductId());
            if (item.getQuantity() == null || item.getQuantity() < 1 || item.getQuantity() > 999) throw new BusinessException("商品数量无效");
            if (product == null || !Integer.valueOf(1).equals(product.getStatus())) throw new BusinessException("商品不存在或已下架");
            if (product.getPrice() == null || product.getPrice().signum() < 0) throw new BusinessException("商品价格无效");
            if (product.getStock() == null || product.getStock() < item.getQuantity()) throw new BusinessException("商品库存不足");
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            snapshot.add(new OrderPreviewItemResponse(product.getId(), product.getTitle(), product.getBrand(), product.getModel(), product.getPrice(), item.getQuantity(), subtotal));
            total = total.add(subtotal);
        }
        var now = store.now();
        var duration = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(10) : ttl;
        var draft = new OrderDraft(UUID.randomUUID().toString(), user, now, now.plus(duration), "PREVIEWED", snapshot, total, "CART", 2);
        return response(store.insert(draft), "PREVIEWED", null);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDraftResponse detail(Long user, String id) {
        var saved = store.load(user, id, false);
        if (saved.orderId() != null) return response(saved, "CONFIRMED", null);
        if (saved.expired()) return response(saved, "EXPIRED", "预览已过期，请重新预览并确认");
        if (!"PREVIEWED".equals(saved.status())) return response(saved, saved.status(), "预览已取消，请重新预览");
        String reason = changed(user, saved.draft());
        return response(saved, reason == null ? "PREVIEWED" : "STALE", reason);
    }

    private String changed(Long user, OrderDraft draft) {
        var cart = carts.selectOne(Wrappers.<CartEntity>lambdaQuery().eq(CartEntity::getUserId, user));
        if (cart == null) return "购物车已变化，请重新预览";
        var current = items.selectList(Wrappers.<CartItemEntity>lambdaQuery().eq(CartItemEntity::getCartId, cart.getId()));
        if (current.size() != draft.items().size()) return "购物车已变化，请重新预览";
        for (var snapshot : draft.items()) {
            if (current.stream().noneMatch(i -> i.getProductId().equals(snapshot.productId()) && i.getQuantity().equals(snapshot.quantity()))) return "购物车已变化，请重新预览";
            var product = products.selectById(snapshot.productId());
            if (product == null || !Integer.valueOf(1).equals(product.getStatus())) return "商品不存在或已下架，请重新预览";
            if (product.getPrice() == null || product.getPrice().compareTo(snapshot.previewUnitPrice()) != 0) return "商品价格已变化，请重新预览";
            if (!Objects.equals(product.getTitle(), snapshot.productTitle()) || !Objects.equals(product.getBrand(), snapshot.brand()) || !Objects.equals(product.getModel(), snapshot.model())) return "商品信息已变化，请重新预览";
            if (product.getStock() == null || product.getStock() < snapshot.quantity()) return "商品库存不足，请调整购物车后重新预览";
        }
        return null;
    }

    @Override
    public OrderDraft get(Long user, String id) {
        var saved = store.load(user, id, false);
        if (saved.expired() || !"PREVIEWED".equals(saved.status())) throw new ConflictException("订单预览已过期或已确认");
        return saved.draft();
    }
    @Override public void delete(Long user, String id) { store.cancel(user, id); }

    private OrderDraftResponse response(OrderDraftStore.Stored saved, String state, String reason) {
        var d = saved.draft();
        return new OrderDraftResponse(d.draftId(), d.createdAt(), d.expiresAt(), state, d.items(), d.previewTotalAmount(), d.source(), d.version(),
                saved.fingerprint(), saved.orderId(), reason);
    }
    private void requireUser(Long user) { if (user == null || user <= 0) throw new UnauthorizedException("未登录或登录已失效"); }
}
