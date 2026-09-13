package com.example.service.cart.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.dto.cart.Request.AddCartItemRequest;
import com.example.dto.cart.Request.UpdateCartItemRequest;
import com.example.dto.cart.Response.CartItemResponse;
import com.example.dto.cart.Response.CartResponse;
import com.example.entity.CartEntity;
import com.example.entity.CartItemEntity;
import com.example.entity.ProductEntity;
import com.example.exception.BusinessException;
import com.example.exception.UnauthorizedException;
import com.example.mapper.CartItemMapper;
import com.example.mapper.CartMapper;
import com.example.service.cart.CartService;
import com.example.service.product.ProductService;
import com.example.utils.Constants;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 登录用户购物车业务服务实现。 */
@Service
public class CartServiceImpl implements CartService {
    private static final int MAX_ITEM_QUANTITY = 999;

    @Resource
    private CartMapper cartMapper;
    @Resource
    private CartItemMapper cartItemMapper;
    @Resource
    private ProductService productService;

    @Override
    @Transactional
    public CartResponse getCart(Long userId) {
        return toResponse(getOrCreateCart(validUserId(userId)));
    }

    @Override
    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest request) {
        Long currentUserId = validUserId(userId);
        validateAddRequest(request);
        CartEntity cart = getOrCreateCartForUpdate(currentUserId);
        ProductEntity product = requirePurchasableProduct(request.getProductId());
        CartItemEntity item = findItem(cart.getId(), product.getId());
        int newQuantity = request.getQuantity();
        if (item != null) {
            long combined = (long) item.getQuantity() + request.getQuantity();
            if (combined > MAX_ITEM_QUANTITY || combined > product.getStock()) {
                throw new BusinessException("商品库存不足");
            }
            newQuantity = (int) combined;
            item.setQuantity(newQuantity);
            item.setDisplayPrice(product.getPrice());
            item.setUpdatedAt(LocalDateTime.now());
            if (cartItemMapper.updateById(item) <= 0) {
                throw new BusinessException("购物车商品更新失败");
            }
        } else {
            if (request.getQuantity() > product.getStock()) {
                throw new BusinessException("商品库存不足");
            }
            LocalDateTime now = LocalDateTime.now();
            item = new CartItemEntity(null, cart.getId(), product.getId(), request.getQuantity(),
                    product.getPrice(), now, now, 0);
            try {
                cartItemMapper.insert(item);
            } catch (DuplicateKeyException exception) {
                throw new BusinessException("购物车商品已被更新，请重试");
            }
        }
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItem(Long userId, Long productId, UpdateCartItemRequest request) {
        Long currentUserId = validUserId(userId);
        validateProductId(productId);
        if (request == null || request.getQuantity() == null
                || request.getQuantity() < 1 || request.getQuantity() > MAX_ITEM_QUANTITY) {
            throw new BusinessException("数量必须在1到" + MAX_ITEM_QUANTITY + "之间");
        }
        CartEntity cart = getExistingCartForUpdate(currentUserId);
        CartItemEntity item = findItemOrThrow(cart, productId);
        ProductEntity product = requirePurchasableProduct(productId);
        if (request.getQuantity() > product.getStock()) {
            throw new BusinessException("商品库存不足");
        }
        item.setQuantity(request.getQuantity());
        item.setDisplayPrice(product.getPrice());
        item.setUpdatedAt(LocalDateTime.now());
        if (cartItemMapper.updateById(item) <= 0) {
            throw new BusinessException("购物车商品不存在");
        }
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long userId, Long productId) {
        Long currentUserId = validUserId(userId);
        validateProductId(productId);
        CartEntity cart = getExistingCartForUpdate(currentUserId);
        CartItemEntity item = findItemOrThrow(cart, productId);
        if (cartItemMapper.deleteById(item.getId()) <= 0) {
            throw new BusinessException("购物车商品不存在");
        }
        return toResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse clear(Long userId) {
        Long currentUserId = validUserId(userId);
        CartEntity cart = getOrCreateCartForUpdate(currentUserId);
        cartItemMapper.update(null, Wrappers.<CartItemEntity>lambdaUpdate()
                .set(CartItemEntity::getDeleted, 1)
                .set(CartItemEntity::getUpdatedAt, LocalDateTime.now())
                .eq(CartItemEntity::getCartId, cart.getId())
                .eq(CartItemEntity::getDeleted, 0));
        return toResponse(cart);
    }

    private CartEntity getOrCreateCart(Long userId) {
        CartEntity cart = cartMapper.selectOne(Wrappers.<CartEntity>lambdaQuery()
                .eq(CartEntity::getUserId, userId));
        if (cart != null) {
            return cart;
        }
        LocalDateTime now = LocalDateTime.now();
        cart = new CartEntity(null, userId, now, now, 0);
        try {
            cartMapper.insert(cart);
            return cart;
        } catch (DuplicateKeyException exception) {
            CartEntity existing = cartMapper.selectOne(Wrappers.<CartEntity>lambdaQuery()
                    .eq(CartEntity::getUserId, userId));
            if (existing == null) {
                throw new BusinessException("购物车创建失败");
            }
            return existing;
        }
    }

    private CartEntity getOrCreateCartForUpdate(Long userId) {
        CartEntity cart = cartMapper.selectOne(Wrappers.<CartEntity>lambdaQuery()
                .eq(CartEntity::getUserId, userId));
        if (cart != null) {
            CartEntity locked = cartMapper.selectByUserIdForUpdate(userId);
            if (locked == null) {
                throw new BusinessException("购物车不存在");
            }
            return locked;
        }
        cart = cartMapper.selectByUserIdForUpdate(userId);
        if (cart != null) {
            return cart;
        }
        LocalDateTime now = LocalDateTime.now();
        CartEntity newCart = new CartEntity(null, userId, now, now, 0);
        try {
            cartMapper.insert(newCart);
            return newCart;
        } catch (DuplicateKeyException exception) {
            CartEntity existing = cartMapper.selectByUserIdForUpdate(userId);
            if (existing == null) {
                throw new BusinessException("购物车创建失败");
            }
            return existing;
        }
    }

    private CartEntity getExistingCartForUpdate(Long userId) {
        CartEntity cart = cartMapper.selectOne(Wrappers.<CartEntity>lambdaQuery()
                .eq(CartEntity::getUserId, userId));
        if (cart != null) {
            CartEntity locked = cartMapper.selectByUserIdForUpdate(userId);
            if (locked == null) {
                throw new BusinessException("购物车不存在");
            }
            cart = locked;
        }
        if (cart == null) {
            throw new BusinessException("购物车商品不存在");
        }
        return cart;
    }

    private CartItemEntity findItem(Long cartId, Long productId) {
        return cartItemMapper.selectOne(Wrappers.<CartItemEntity>lambdaQuery()
                .eq(CartItemEntity::getCartId, cartId)
                .eq(CartItemEntity::getProductId, productId));
    }

    private CartItemEntity findItemOrThrow(CartEntity cart, Long productId) {
        CartItemEntity item = findItem(cart.getId(), productId);
        if (item == null) {
            throw new BusinessException("购物车商品不存在");
        }
        return item;
    }

    private ProductEntity requirePurchasableProduct(Long productId) {
        ProductEntity product = productService.findById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        if (!ON_SALEEquals(product.getStatus())) {
            throw new BusinessException("商品已下架，无法加入购物车");
        }
        if (product.getStock() == null) {
            throw new BusinessException("商品库存不足");
        }
        return product;
    }

    private boolean ON_SALEEquals(Integer status) {
        return status != null && status == Constants.ON_SALE;
    }

    private CartResponse toResponse(CartEntity cart) {
        List<CartItemResponse> items = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<CartItemEntity> entities = cartItemMapper.selectList(Wrappers.<CartItemEntity>lambdaQuery()
                .eq(CartItemEntity::getCartId, cart.getId())
                .orderByAsc(CartItemEntity::getId));
        for (CartItemEntity item : entities) {
            ProductEntity product = productService.findById(item.getProductId());
            if (product == null) {
                continue;
            }
            BigDecimal price = product.getPrice() == null ? item.getDisplayPrice() : product.getPrice();
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(item.getQuantity()));
            items.add(new CartItemResponse(product.getId(), product.getBrand(), product.getModel(),
                    product.getTitle(), price, product.getStock(), item.getQuantity(), product.getStatus(), subtotal));
            totalQuantity += item.getQuantity();
            totalAmount = totalAmount.add(subtotal);
        }
        return new CartResponse(cart.getId(), List.copyOf(items), totalQuantity, totalAmount);
    }

    private Long validUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("未登录或登录已失效");
        }
        return userId;
    }

    private void validateAddRequest(AddCartItemRequest request) {
        if (request == null || request.getProductId() == null || request.getProductId() <= 0) {
            throw new BusinessException("商品ID必须是正整数");
        }
        if (request.getQuantity() == null || request.getQuantity() < 1
                || request.getQuantity() > MAX_ITEM_QUANTITY) {
            throw new BusinessException("数量必须在1到" + MAX_ITEM_QUANTITY + "之间");
        }
    }

    private void validateProductId(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException("商品ID必须是正整数");
        }
    }
}
