package com.example.dto.product.Request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 商品列表和 AI 内部商品检索查询参数。 */
@Data
public class ProductQueryRequest {
    @Size(max = 255, message = "关键词长度不能超过255个字符")
    private String keyword;
    @Size(max = 100, message = "品牌长度不能超过100个字符")
    private String brand;
    @DecimalMin(value = "0.00", message = "最低价格不能小于0")
    @Digits(integer = 10, fraction = 2, message = "最低价格最多支持10位整数和2位小数")
    private BigDecimal minPrice;
    @DecimalMin(value = "0.00", message = "最高价格不能小于0")
    @Digits(integer = 10, fraction = 2, message = "最高价格最多支持10位整数和2位小数")
    private BigDecimal maxPrice;
    @Min(value = 1, message = "最低内存必须大于0")
    private Integer minRamGb;
    @Min(value = 1, message = "最低存储必须大于0")
    private Integer minStorageGb;
    @Min(value = 0, message = "商品状态只能是0或1")
    @Max(value = 1, message = "商品状态只能是0或1")
    private Integer status;
    @Min(value = 1, message = "页码必须大于0")
    private long page = 1;
    @Min(value = 1, message = "每页数量必须大于0")
    @Max(value = 100, message = "每页数量不能超过100")
    private long size = 20;
    @Size(max = 30, message = "排序字段长度不能超过30个字符")
    private String sortBy = "createdAt";
    @Pattern(regexp = "(?i)asc|desc", message = "排序方向只能是asc或desc")
    private String sortDirection = "desc";
}
