package com.example.dto.order.Response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单预览草稿；草稿不是订单，不包含订单号或订单状态。 */
public record OrderDraftResponse(
        String draftId,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        String status,
        List<OrderPreviewItemResponse> items,
        BigDecimal previewTotalAmount,
        String source,
        int version) {
    public OrderDraftResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
