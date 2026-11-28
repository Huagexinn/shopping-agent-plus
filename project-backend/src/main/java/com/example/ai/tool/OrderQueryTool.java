package com.example.ai.tool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.ai.model.request.OrderQueryToolRequest;
import com.example.ai.model.response.OrderQueryToolResult;
import com.example.dto.order.Request.OrderQueryRequest;
import com.example.dto.order.Response.OrderResponse;
import com.example.dto.order.Response.OrderSummaryResponse;
import com.example.exception.BusinessException;
import com.example.service.order.OrderService;
import com.example.utils.Constants;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.QueryTimeoutException;

import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** 仅通过 OrderService 查询当前认证用户订单的只读 AI Tool。 */
@Service
public class OrderQueryTool {
    private static final String TOOL_NAME = "OrderQueryTool";

    @Resource
    private OrderService orderService;

    /** 执行白名单中的 list、detail 查询操作。 */
    @Transactional(readOnly = true)
    public OrderQueryToolResult execute(Long userId, OrderQueryToolRequest request) {
        NormalizedRequest normalized = normalize(request);
        Long currentUserId = requireUserId(userId);
        try {
            if ("list".equals(normalized.operation())) {
                OrderQueryRequest queryRequest = new OrderQueryRequest();
                queryRequest.setPage(normalized.page());
                queryRequest.setSize(normalized.size());
                Page<OrderSummaryResponse> page = orderService.page(currentUserId, queryRequest);
                List<OrderSummaryResponse> orders = page.getRecords() == null ? List.of() : page.getRecords();
                String message = orders.isEmpty() ? "当前用户暂无订单" : "已读取当前用户订单列表";
                return new OrderQueryToolResult(TOOL_NAME, true, "list", message,
                        orders, null, page.getCurrent(), page.getSize(), page.getTotal());
            }
            OrderResponse order = orderService.findDetail(currentUserId, normalized.orderId());
            return new OrderQueryToolResult(TOOL_NAME, true, "detail", "已读取当前用户订单详情",
                    List.of(), order, 1, 1, 1);
        } catch (BusinessException exception) {
            return new OrderQueryToolResult(TOOL_NAME, false, normalized.operation(), exception.getMessage(),
                    List.of(), null, normalized.page(), normalized.size(), 0);
        } catch (RuntimeException exception) {
            String message = isTimeout(exception)
                    ? "订单查询超时，请稍后重试"
                    : "订单查询暂不可用，请稍后重试";
            return new OrderQueryToolResult(TOOL_NAME, false, normalized.operation(), message,
                    List.of(), null, normalized.page(), normalized.size(), 0, "SYSTEM_FAILURE");
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException
                    || current instanceof SQLTimeoutException || current instanceof QueryTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private NormalizedRequest normalize(OrderQueryToolRequest request) {
        if (request == null || request.operation() == null || request.operation().isBlank()) {
            throw new BusinessException("订单查询操作不能为空");
        }
        String operation = request.operation().trim().toLowerCase(Locale.ROOT);
        if (!operation.matches("list|detail")) {
            throw new BusinessException("不支持的订单查询操作");
        }
        if ("detail".equals(operation)) {
            if (request.orderId() == null || request.orderId() <= 0) {
                throw new BusinessException("订单ID必须是正整数");
            }
            return new NormalizedRequest(operation, request.orderId(), 1L, 1L);
        }
        if (request.orderId() != null) {
            throw new BusinessException("查询订单列表不需要订单ID");
        }
        long page = request.page() == null ? 1L : request.page();
        long size = request.size() == null ? 20L : request.size();
        if (page < 1) {
            throw new BusinessException("页码必须大于0");
        }
        if (size < 1 || size > Constants.MAX_PAGE_SIZE) {
            throw new BusinessException("每页数量必须在1到" + Constants.MAX_PAGE_SIZE + "之间");
        }
        return new NormalizedRequest(operation, null, page, size);
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("未登录或登录已失效");
        }
        return userId;
    }

    private record NormalizedRequest(String operation, Long orderId, Long page, Long size) {
    }
}
