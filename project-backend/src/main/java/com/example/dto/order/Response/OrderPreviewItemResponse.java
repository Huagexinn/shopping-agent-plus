package com.example.dto.order.Response;

import java.math.BigDecimal;

/** 订单预览明细；金额是确认前的展示快照，不代表最终结算金额。 */
public record OrderPreviewItemResponse(
        Long productId,
        String productTitle,
        String brand,
        String model,
        BigDecimal previewUnitPrice,
        Integer quantity,
        BigDecimal previewSubtotal) {
}
