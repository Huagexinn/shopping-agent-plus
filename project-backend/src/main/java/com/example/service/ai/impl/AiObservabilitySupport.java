package com.example.service.ai.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** AI 调用日志的脱敏、白名单摘要和结果分类工具。 */
final class AiObservabilitySupport {
    static final String SUCCESS = "SUCCESS";
    static final String PARTIAL_SUCCESS = "PARTIAL_SUCCESS";
    static final String BUSINESS_FAILURE = "BUSINESS_FAILURE";
    static final String TIMEOUT = "TIMEOUT";
    static final String SYSTEM_FAILURE = "SYSTEM_FAILURE";
    static final String UNAVAILABLE = "UNAVAILABLE";

    private static final int MAX_ARGUMENT_SUMMARY_LENGTH = 256;
    private static final Set<String> NUMERIC_ARGUMENT_FIELDS = Set.of(
            "productId", "page", "size", "quantity", "requiredQuantity",
            "minPrice", "maxPrice", "minRamGb", "minStorageGb", "orderId");
    private static final ObjectMapper JSON = new ObjectMapper();

    private AiObservabilitySupport() {
    }

    static String userKey(Long userId) {
        if (userId == null) {
            return UNAVAILABLE;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                value.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            return UNAVAILABLE;
        }
    }

    static String summarizeToolArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode root = JSON.readTree(arguments);
            if (root == null || !root.isObject()) {
                return "INVALID_JSON";
            }
            List<String> fields = new ArrayList<>();
            collectFields(root, fields);
            String summary = "{" + String.join(",", fields) + "}";
            return summary.length() <= MAX_ARGUMENT_SUMMARY_LENGTH
                    ? summary : summary.substring(0, MAX_ARGUMENT_SUMMARY_LENGTH) + "...";
        } catch (Exception exception) {
            return "INVALID_JSON";
        }
    }

    private static void collectFields(JsonNode object, List<String> fields) {
        Iterator<Map.Entry<String, JsonNode>> iterator = object.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if ("keyword".equals(name) || "brand".equals(name)) {
                fields.add(name + "Length=" + (value.isTextual() ? value.textValue().length() : 0));
            } else if ("productIds".equals(name)) {
                fields.add("productIdsCount=" + (value.isArray() ? value.size() : 0));
            } else if ("operation".equals(name)) {
                fields.add("operation=" + safeOperation(value));
            } else if (NUMERIC_ARGUMENT_FIELDS.contains(name) && value.isNumber()) {
                fields.add(name + "=" + value.asText());
            } else if (value.isObject()) {
                collectFields(value, fields);
            }
        }
    }

    private static String safeOperation(JsonNode value) {
        if (!value.isTextual()) {
            return UNAVAILABLE;
        }
        String operation = value.textValue().toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "get", "add", "update", "remove", "clear", "list", "detail" -> operation;
            default -> "OTHER";
        };
    }

    static String tokenUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return UNAVAILABLE;
        }
        Usage usage = response.getMetadata().getUsage();
        Integer input = usage.getPromptTokens();
        Integer output = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        if (input == null && output == null && total == null) {
            return UNAVAILABLE;
        }
        return "input=" + numberOrUnavailable(input)
                + ",output=" + numberOrUnavailable(output)
                + ",total=" + numberOrUnavailable(total);
    }

    static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return UNAVAILABLE;
        }
        String finishReason = response.getResult().getMetadata().getFinishReason();
        return finishReason == null || finishReason.isBlank() ? UNAVAILABLE : finishReason;
    }

    static String providerHttpStatus(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return UNAVAILABLE;
        }
        for (String key : List.of("httpStatusCode", "httpStatus", "statusCode")) {
            Object value = response.getMetadata().get(key);
            if (value instanceof Number number) {
                return String.valueOf(number.intValue());
            }
            if (value instanceof String text && text.matches("[1-5]\\d{2}")) {
                return text;
            }
        }
        return UNAVAILABLE;
    }

    static String providerHttpStatus(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof WebClientResponseException responseException) {
                return String.valueOf(responseException.getStatusCode().value());
            }
            if (current instanceof RestClientResponseException responseException) {
                return String.valueOf(responseException.getStatusCode().value());
            }
            current = current.getCause();
        }
        return UNAVAILABLE;
    }

    static String classifyFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return TIMEOUT;
            }
            if (current instanceof com.example.exception.BusinessException) {
                return BUSINESS_FAILURE;
            }
            current = current.getCause();
        }
        return SYSTEM_FAILURE;
    }

    private static String numberOrUnavailable(Integer value) {
        return value == null ? UNAVAILABLE : value.toString();
    }
}
