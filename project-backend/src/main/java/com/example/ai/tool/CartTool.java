package com.example.ai.tool;

import com.example.ai.model.request.CartToolRequest;
import com.example.ai.model.response.CartToolResult;
import com.example.dto.cart.Request.AddCartItemRequest;
import com.example.dto.cart.Request.UpdateCartItemRequest;
import com.example.dto.cart.Response.CartResponse;
import com.example.exception.BusinessException;
import com.example.service.ai.CartOperationIdempotencyService;
import com.example.service.cart.CartService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.dao.QueryTimeoutException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.net.SocketTimeoutException;
import java.sql.SQLTimeoutException;

/** 仅通过 CartService 操作当前认证用户购物车的 AI Tool。 */
@Service
public class CartTool {
    private static final String TOOL_NAME = "CartTool";
    private static final int MAX_ITEM_QUANTITY = 999;

    @Resource
    private CartService cartService;
    @Resource
    private CartOperationIdempotencyService operationIdempotencyService;

    /** 执行白名单中的 add、get、update、remove 操作。 */
    public CartToolResult execute(Long userId, CartToolRequest request) {
        NormalizedRequest normalized = normalize(request);
        try {
            if ("get".equals(normalized.operation())) {
                return success(normalized.operation(), "已读取当前用户购物车",
                        cartService.getCart(requireUserId(userId)), null);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return failure(normalized.operation(), exception);
        }
        Long currentUserId = requireUserId(userId);
        String fingerprint = fingerprint(normalized);
        try {
            return operationIdempotencyService.execute(currentUserId, normalized.operationId(), fingerprint,
                    () -> executeWrite(currentUserId, normalized));
        } catch (BusinessException exception) {
            if (exception instanceof com.example.exception.ConflictException) throw exception;
            // 到这里事务已经回滚；不能在事务内吞掉异常，否则代理会抛 UnexpectedRollbackException。
            return new CartToolResult(TOOL_NAME, false, normalized.operation(), exception.getMessage(), null,
                    normalized.productId(), "BUSINESS_FAILURE");
        } catch (RuntimeException exception) {
            return failure(normalized.operation(), exception, normalized.productId());
        }
    }


    private CartToolResult executeWrite(Long userId, NormalizedRequest request) {
        try {
            CartResponse cart;

            switch (request.operation()) {
                case "add" -> {
                    AddCartItemRequest addRequest = new AddCartItemRequest();
                    addRequest.setProductId(request.productId());
                    addRequest.setQuantity(request.quantity());
                    cart = cartService.addItem(userId, addRequest);
                }
                case "update" -> {
                    UpdateCartItemRequest updateRequest = new UpdateCartItemRequest();
                    updateRequest.setQuantity(request.quantity());
                    cart = cartService.updateItem(
                            userId,
                            request.productId(),
                            updateRequest
                    );
                }
                case "remove" ->
                        cart = cartService.removeItem(userId, request.productId());

                default -> throw new BusinessException("不支持的购物车操作");
            }

            return success(
                    request.operation(),
                    operationMessage(request.operation()),
                    cart,
                    request.productId()
            );

        } catch (BusinessException exception) {
            throw exception;
        }
    }


    private CartToolResult failure(String operation, RuntimeException exception) {
        return failure(operation, exception, null);
    }

    private CartToolResult failure(String operation, RuntimeException exception, Long productId) {
        if (isTimeout(exception)) {
            return new CartToolResult(TOOL_NAME, false, operation,
                    "购物车操作超时，请查询原任务结果", null, productId, "SYSTEM_FAILURE");
        }
        return new CartToolResult(TOOL_NAME, false, operation,
                "购物车操作暂不可用，请先核对原任务结果", null, productId, "SYSTEM_FAILURE");
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

    private NormalizedRequest normalize(CartToolRequest request) {
        if (request == null || request.operation() == null || request.operation().isBlank()) {
            throw new BusinessException("购物车操作不能为空");
        }
        String operation = request.operation().trim().toLowerCase(Locale.ROOT);
        if (!operation.matches("add|get|update|remove")) {
            throw new BusinessException("不支持的购物车操作");
        }
        if ("get".equals(operation)) {
            if (request.productId() != null || request.quantity() != null || request.operationId() != null) {
                throw new BusinessException("查看购物车不需要商品、数量或操作标识");
            }
            return new NormalizedRequest(operation, null, null, null);
        }
        validateProductId(request.productId());
        validateOperationId(request.operationId());
        if ("remove".equals(operation)) {
            if (request.quantity() != null) {
                throw new BusinessException("删除购物车商品不需要数量");
            }
            return new NormalizedRequest(operation, request.productId(), null, request.operationId().trim());
        }
        if (request.quantity() == null || request.quantity() < 1 || request.quantity() > MAX_ITEM_QUANTITY) {
            throw new BusinessException("数量必须在1到" + MAX_ITEM_QUANTITY + "之间");
        }
        return new NormalizedRequest(operation, request.productId(), request.quantity(), request.operationId().trim());
    }

    private CartToolResult success(String operation, String message, CartResponse cart, Long changedProductId) {
        return new CartToolResult(TOOL_NAME, true, operation, message, cart, changedProductId);
    }

    private String operationMessage(String operation) {
        return switch (operation) {
            case "add" -> "已添加商品到购物车";
            case "update" -> "已更新购物车商品数量";
            case "remove" -> "已删除购物车商品";
            default -> "购物车操作完成";
        };
    }

    private String fingerprint(NormalizedRequest request) {
        return com.example.ai.tool.support.CartOperationIdentity.fingerprint(request.operation(), request.productId(), request.quantity());
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("未登录或登录已失效");
        }
        return userId;
    }

    private void validateProductId(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException("商品ID必须是正整数");
        }
    }

    private void validateOperationId(String operationId) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 128
                || operationId.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("操作标识不能为空且不能包含控制字符");
        }
    }

    private record NormalizedRequest(String operation, Long productId, Integer quantity, String operationId) {
    }
}
