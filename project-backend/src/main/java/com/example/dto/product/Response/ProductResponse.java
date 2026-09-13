package com.example.dto.product.Response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 对外返回的手机商品信息，不暴露持久化实体对象。 */
public record ProductResponse(
        Long id,
        String brand,
        String model,
        String title,
        String description,
        BigDecimal price,
        Integer stock,
        Integer ramGb,
        Integer storageGb,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    /** 兼容旧响应构造方式，历史测试仍可按旧字段顺序创建商品响应。 */
    public ProductResponse(Long id,
                           String brand,
                           String model,
                           String title,
                           String description,
                           BigDecimal price,
                           Integer stock,
                           Integer status,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
        this(id, brand, model, title, description, price, stock, null, null, status, createdAt, updatedAt);
    }
}
