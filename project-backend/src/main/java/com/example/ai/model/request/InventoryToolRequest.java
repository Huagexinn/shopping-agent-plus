package com.example.ai.model.request;

import java.util.List;

/** 只读库存查询 Tool 的输入参数。 */
public record InventoryToolRequest(
        List<Long> productIds,
        Integer requiredQuantity) {
}
