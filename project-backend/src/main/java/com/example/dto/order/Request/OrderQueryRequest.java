package com.example.dto.order.Request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 当前用户订单列表分页请求。 */
@Data
public class OrderQueryRequest {
    @Min(value = 1, message = "页码必须大于0")
    private long page = 1;

    @Min(value = 1, message = "每页数量必须大于0")
    @Max(value = 100, message = "每页数量不能超过100")
    private long size = 20;
}
