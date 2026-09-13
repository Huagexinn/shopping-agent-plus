package com.example.service.order;

import com.example.dto.order.Response.OrderPreviewItemResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单草稿的服务层快照，包含归属用户但不直接作为公开响应返回。 */
public record OrderDraft(
        String draftId,
        Long userId,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        String status,
        List<OrderPreviewItemResponse> items,
        BigDecimal previewTotalAmount,
        String source,
        int version) {
    public OrderDraft {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
