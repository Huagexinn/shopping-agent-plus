package com.example.service.product.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.dto.product.Request.ProductQueryRequest;
import com.example.dto.product.Response.ProductResponse;
import com.example.entity.ProductEntity;
import com.example.exception.BusinessException;
import com.example.mapper.ProductMapper;
import com.example.service.product.ProductService;
import com.example.utils.Constants;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 手机商品查询和库存状态服务实现。 */
@Service
public class ProductServiceImpl implements ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);
    private static final String DETAIL_CACHE_PREFIX = "shopping:product:detail:";
    private static final Duration DETAIL_CACHE_TTL = Duration.ofMinutes(10);
    private static final ObjectMapper CACHE_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Map<String, SFunction<ProductEntity, ?>> SORT_COLUMNS = Map.of(
            "price", ProductEntity::getPrice,
            "createdAt", ProductEntity::getCreatedAt,
            "stock", ProductEntity::getStock);

    @Resource
    private ProductMapper productMapper;
    @Resource
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> page(ProductQueryRequest request) {
        return queryPage(request, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> search(ProductQueryRequest request) {
        return queryPage(request, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse findPublicById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        ProductResponse cached = readDetailCache(id);
        if (cached != null) {
            return cached;
        }

        ProductEntity product = productMapper.selectOne(new LambdaQueryWrapper<ProductEntity>()
                .eq(ProductEntity::getId, id)
                .eq(ProductEntity::getStatus, Constants.ON_SALE));
        if (product == null) {
            deleteDetailCache(id);
            return null;
        }
        ProductResponse response = toResponse(product);
        writeDetailCache(id, response);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> findPublicByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> normalizedIds = ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ProductResponse> products = productMapper.selectBatchIds(normalizedIds).stream()
                .filter(product -> product.getStatus() != null && product.getStatus() == Constants.ON_SALE)
                .collect(java.util.stream.Collectors.toMap(ProductEntity::getId, this::toResponse, (left, right) -> left));
        return normalizedIds.stream()
                .map(products::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> listPublicForKnowledge() {
        return productMapper.selectList(new LambdaQueryWrapper<ProductEntity>()
                        .eq(ProductEntity::getStatus, Constants.ON_SALE)
                        .orderByAsc(ProductEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductEntity findById(Long id) {
        if (id == null || id <= 0) {
            return null;
        }
        return productMapper.selectById(id);
    }

    private Page<ProductResponse> queryPage(ProductQueryRequest request, boolean includeKeyword) {
        ProductQueryRequest normalized = normalize(request);
        LambdaQueryWrapper<ProductEntity> wrapper = new LambdaQueryWrapper<ProductEntity>()
                .eq(ProductEntity::getStatus, Constants.ON_SALE);
        applyConditions(wrapper, normalized, includeKeyword);

        Page<ProductEntity> entityPage = productMapper.selectPage(
                new Page<>(normalized.getPage(), normalized.getSize()), wrapper);
        Page<ProductResponse> responsePage = new Page<>(
                entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        responsePage.setRecords(entityPage.getRecords().stream().map(this::toResponse).toList());
        return responsePage;
    }

    private void applyConditions(LambdaQueryWrapper<ProductEntity> wrapper,
                                 ProductQueryRequest request,
                                 boolean includeKeyword) {
        if (includeKeyword && request.getKeyword() != null) {
            String keyword = request.getKeyword();
            wrapper.and(condition -> condition
                    .like(ProductEntity::getBrand, keyword)
                    .or()
                    .like(ProductEntity::getModel, keyword)
                    .or()
                    .like(ProductEntity::getTitle, keyword)
                    .or()
                    .like(ProductEntity::getDescription, keyword));
        }
        if (request.getBrand() != null) {
            wrapper.eq(ProductEntity::getBrand, request.getBrand());
        }
        if (request.getMinPrice() != null) {
            wrapper.ge(ProductEntity::getPrice, request.getMinPrice());
        }
        if (request.getMaxPrice() != null) {
            wrapper.le(ProductEntity::getPrice, request.getMaxPrice());
        }
        if (request.getMinRamGb() != null) {
            wrapper.ge(ProductEntity::getRamGb, request.getMinRamGb());
        }
        if (request.getMinStorageGb() != null) {
            wrapper.ge(ProductEntity::getStorageGb, request.getMinStorageGb());
        }
        if (request.getStatus() != null && request.getStatus() != Constants.ON_SALE) {
            wrapper.eq(ProductEntity::getStatus, request.getStatus());
        }

        boolean ascending = "asc".equalsIgnoreCase(request.getSortDirection());
        wrapper.orderBy(true, ascending, SORT_COLUMNS.get(request.getSortBy()))
                .orderByDesc(ProductEntity::getId);
    }

    private ProductQueryRequest normalize(ProductQueryRequest request) {
        ProductQueryRequest normalized = getProductQueryRequest(request);

        String sortBy = StringUtils.trimToNull(normalized.getSortBy());
        if (sortBy == null) {
            sortBy = "createdAt";
        }
        if (!SORT_COLUMNS.containsKey(sortBy)) {
            throw new BusinessException("不支持的排序字段");
        }
        String sortDirection = StringUtils.trimToNull(normalized.getSortDirection());
        if (sortDirection == null) {
            sortDirection = "desc";
        }
        if (!"asc".equalsIgnoreCase(sortDirection) && !"desc".equalsIgnoreCase(sortDirection)) {
            throw new BusinessException("排序方向只能是asc或desc");
        }

        normalized.setKeyword(StringUtils.trimToNull(normalized.getKeyword()));
        normalized.setBrand(StringUtils.trimToNull(normalized.getBrand()));
        normalized.setSortBy(sortBy);
        normalized.setSortDirection(sortDirection.toLowerCase(Locale.ROOT));
        return normalized;
    }

    private static @NonNull ProductQueryRequest getProductQueryRequest(ProductQueryRequest request) {
        ProductQueryRequest normalized = request == null ? new ProductQueryRequest() : request;
        if (normalized.getPage() < 1) {
            throw new BusinessException("页码必须大于0");
        }
        if (normalized.getSize() < 1 || normalized.getSize() > Constants.MAX_PAGE_SIZE) {
            throw new BusinessException("每页数量必须在1到" + Constants.MAX_PAGE_SIZE + "之间");
        }
        BigDecimal minPrice = normalized.getMinPrice();
        BigDecimal maxPrice = normalized.getMaxPrice();
        if (minPrice != null && minPrice.signum() < 0) {
            throw new BusinessException("最低价格不能小于0");
        }
        if (maxPrice != null && maxPrice.signum() < 0) {
            throw new BusinessException("最高价格不能小于0");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BusinessException("最低价格不能高于最高价格");
        }
        if (normalized.getMinRamGb() != null && normalized.getMinRamGb() < 1) {
            throw new BusinessException("最低内存必须大于0");
        }
        if (normalized.getMinStorageGb() != null && normalized.getMinStorageGb() < 1) {
            throw new BusinessException("最低存储必须大于0");
        }
        if (normalized.getStatus() != null
                && normalized.getStatus() != 0
                && normalized.getStatus() != Constants.ON_SALE) {
            throw new BusinessException("商品状态只能是0或1");
        }
        return normalized;
    }

    private ProductResponse toResponse(ProductEntity product) {
        return new ProductResponse(
                product.getId(),
                product.getBrand(),
                product.getModel(),
                product.getTitle(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getRamGb(),
                product.getStorageGb(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }

    /** 先读 Redis；缓存命中直接返回，未命中再回源 MySQL。缓存 TTL 内商品字段允许短暂陈旧。 */
    private ProductResponse readDetailCache(Long productId) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) {
            return null;
        }
        String key = DETAIL_CACHE_PREFIX + productId;
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            ProductResponse cached = CACHE_MAPPER.readValue(value, ProductResponse.class);
            if (cached == null || !Objects.equals(productId, cached.id())
                    || cached.status() == null || cached.status() != Constants.ON_SALE) {
                deleteDetailCache(productId);
                return null;
            }
            return cached;
        } catch (Exception exception) {
            log.warn("Redis商品详情缓存读取失败，回源商品服务");
            deleteDetailCache(productId);
            return null;
        }
    }

    private void writeDetailCache(Long productId, ProductResponse value) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(
                    DETAIL_CACHE_PREFIX + productId,
                    CACHE_MAPPER.writeValueAsString(value),
                    DETAIL_CACHE_TTL);
        } catch (Exception exception) {
            log.warn("Redis商品详情缓存写入失败，继续使用数据库结果");
        }
    }

    @Override
    public void evictDetailCache(Long productId) {
        deleteDetailCache(productId);
    }

    private void deleteDetailCache(Long productId) {
        StringRedisTemplate redis = redisTemplate();
        if (redis == null || productId == null || productId <= 0) {
            return;
        }
        try {
            redis.delete(DETAIL_CACHE_PREFIX + productId);
        } catch (RuntimeException exception) {
            log.warn("Redis商品详情缓存清理失败");
        }
    }

    private StringRedisTemplate redisTemplate() {
        if (redisTemplateProvider == null) {
            return null;
        }
        try {
            return redisTemplateProvider.getIfAvailable();
        } catch (RuntimeException exception) {
            log.warn("Redis商品详情缓存依赖不可用，回源商品服务");
            return null;
        }
    }
}
