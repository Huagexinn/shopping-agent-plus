package com.example.ai.tool.support;

import com.example.ai.model.request.CartToolRequest;
import com.example.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** 正常执行、对账、恢复共用同一套操作标识和参数指纹。 */
public final class CartOperationIdentity {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CartOperationIdentity() {}

    public static CartToolRequest request(String clientId, String operation, Long product, Integer quantity) {
        if (operation == null) throw new BusinessException("购物车操作不能为空");
        String op = operation.trim().toLowerCase(Locale.ROOT);
        return new CartToolRequest(op, product, quantity, "get".equals(op) ? null
                : "ai-cart-v1:" + clientId + ":" + op + ":" + product);
    }

    public static CartToolRequest fromArguments(String clientId, String arguments) {
        try {
            var root = JSON.readTree(arguments);
            var request = JSON.treeToValue(root.required("request"), SavedRequest.class);
            var result = request(clientId, request.operation(), request.productId(), request.quantity());
            if (!result.operation().matches("add|get|update|remove")) throw new IllegalArgumentException();
            if ("get".equals(result.operation())) {
                if (result.productId() != null || result.quantity() != null) throw new IllegalArgumentException();
            } else {
                if (result.productId() == null || result.productId() <= 0) throw new IllegalArgumentException();
                if ("remove".equals(result.operation())) {
                    if (result.quantity() != null) throw new IllegalArgumentException();
                } else if (result.quantity() == null || result.quantity() < 1 || result.quantity() > 999) {
                    throw new IllegalArgumentException();
                }
            }
            return result;
        } catch (Exception e) { throw new BusinessException("保存的购物车参数无效，不能恢复执行"); }
    }

    public static String fingerprint(String operation, Long product, Integer quantity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((operation + ":" + product + ":" + quantity).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private record SavedRequest(String operation, Long productId, Integer quantity) {}
}
