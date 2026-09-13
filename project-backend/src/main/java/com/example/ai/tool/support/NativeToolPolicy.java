package com.example.ai.tool.support;

import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.rag.HybridRagService;
import com.example.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单轮模型 Tool 调用的次数、去重和候选商品上下文约束。 */
public final class NativeToolPolicy {
    public static final int MAX_TOOL_CALLS = 7;
    public static final int MAX_ARGUMENT_LENGTH = 4000;

    private final ObjectMapper objectMapper;
    private final Set<String> callFingerprints = new LinkedHashSet<>();
    private final Set<Long> knownProductIds = new LinkedHashSet<>();

    public NativeToolPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized void beforeCall(String toolName, String arguments, int executedCallCount) {
        if (executedCallCount >= MAX_TOOL_CALLS) {
            throw new BusinessException("本轮 Tool 调用次数已达上限，请基于已有结果直接回答");
        }
        if (arguments != null && arguments.length() > MAX_ARGUMENT_LENGTH) {
            throw new BusinessException("Tool 参数过长，已拒绝执行");
        }
        String fingerprint = toolName + ":" + canonicalArguments(arguments);
        if (!callFingerprints.add(fingerprint)) {
            throw new BusinessException("本轮已使用相同参数调用过 " + toolName + "，请基于已有结果继续");
        }
    }

    public synchronized void recordResult(Object result) {
        if (result instanceof HybridRagService.RetrievalResult retrieval) {
            seedProductIds(retrieval.productIds());
            return;
        }
        if (!(result instanceof ReadOnlyToolResult readOnlyResult)) {
            return;
        }
        if (readOnlyResult.hasUsableFacts()) {
            knownProductIds.addAll(readOnlyResult.productIds());
        }
    }

    public synchronized void seedProductIds(List<Long> productIds) {
        if (productIds != null) {
            knownProductIds.addAll(productIds.stream()
                    .filter(productId -> productId != null && productId > 0)
                    .toList());
        }
    }

    public synchronized void requireKnownProductIds(String toolName, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        List<Long> unknownIds = productIds.stream()
                .filter(productId -> !knownProductIds.contains(productId))
                .distinct()
                .toList();
        if (!unknownIds.isEmpty()) {
            throw new BusinessException(toolName + "只能处理已由混合 RAG 或 ProductDetailTool 返回的商品ID");
        }
    }

    private String canonicalArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            return canonicalize(objectMapper.readTree(arguments)).toString();
        } catch (Exception ignored) {
            return arguments.trim();
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = objectMapper.createObjectNode();
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        node.fields().forEachRemaining(fields::add);
        fields.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, JsonNode> field : fields) {
            object.set(field.getKey(), canonicalize(field.getValue()));
        }
        return object;
    }
}
