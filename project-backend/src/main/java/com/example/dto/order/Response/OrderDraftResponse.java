package com.example.dto.order.Response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 不可变订单预览及当前可用性；确认后可通过 confirmedOrderId 查询原订单。 */
public record OrderDraftResponse(
        String draftId,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        String status,
        List<OrderPreviewItemResponse> items,
        BigDecimal previewTotalAmount,
        String source,
        int version,
        String fingerprint,
        Long confirmedOrderId,
        String unavailableReason) {
    public OrderDraftResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
