package com.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 手机商品持久化实体。 */
@TableName("products")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProductEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String brand;
    private String model;
    private String title;
    private String description;
    private BigDecimal price;
    private Integer stock;
    @TableField("ram_gb")
    private Integer ramGb;
    @TableField("storage_gb")
    private Integer storageGb;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;

    /** 兼容旧构造方式，历史测试和数据初始化仍可按旧字段顺序创建商品。 */
    public ProductEntity(Long id,
                         String brand,
                         String model,
                         String title,
                         String description,
                         BigDecimal price,
                         Integer stock,
                         Integer status,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt,
                         Integer deleted) {
        this(id, brand, model, title, description, price, stock, null, null, status, createdAt, updatedAt, deleted);
    }
}
