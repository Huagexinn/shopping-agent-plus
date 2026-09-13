package com.example.service.ai.impl;

import com.example.ai.model.response.CartToolResult;
import com.example.ai.model.response.OrderQueryToolResult;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.rag.HybridRagService;
import com.example.ai.rag.KnowledgeReference;
import com.example.ai.tool.support.NativeToolPolicy;
import com.example.dto.ai.Response.AiToolCallResponse;
import com.example.dto.cart.Response.CartItemResponse;
import com.example.dto.order.Response.OrderDraftResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 记录单轮模型 Tool 请求、执行结果和安全策略状态。 */
public final class NativeToolAudit {
    private static final Logger log = LoggerFactory.getLogger(NativeToolAudit.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final String aiTraceId;
    private final String requestId;
    private final String conversationId;
    private final String userKey;
    private final List<NativeToolAuditEntry> entries = new ArrayList<>();
    private final List<KnowledgeReference> knowledgeReferences = new ArrayList<>();
    private final NativeToolPolicy policy = new NativeToolPolicy(JSON);
    private int sequence;

    public NativeToolAudit(String aiTraceId, String requestId, String conversationId, String userKey) {
        this.aiTraceId = aiTraceId;
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.userKey = userKey;
    }

    synchronized NativeToolAuditEntry start(String toolName, String arguments) {
        policy.beforeCall(toolName, arguments, entries.size());
        NativeToolAuditEntry entry = new NativeToolAuditEntry(++sequence, toolName, arguments,
                LocalDateTime.now(), System.nanoTime(), null, null, null, null);
        entries.add(entry);
        log.info("AI_TOOL_REQUEST aiTraceId={} reqId={} conversationId={} userKey={} seq={} toolName={} toolArgsSummary={}",
                aiTraceId, requestId, conversationId, userKey, entry.sequence(), toolName,
                AiObservabilitySupport.summarizeToolArgs(arguments));
        return entry;
    }

    synchronized void complete(NativeToolAuditEntry entry, Object result) {
        policy.recordResult(result);
        if (result instanceof HybridRagService.RetrievalResult retrieval) {
            knowledgeReferences.addAll(retrieval.references());
        }
        NativeToolAuditEntry updated = new NativeToolAuditEntry(entry.sequence(), entry.toolName(), entry.arguments(),
                entry.requestedAt(), entry.startedAtNanos(), result, null,
                resultOutcome(result), elapsedMillis(entry.startedAtNanos()));
        replace(entry, updated);
        log.info("AI_TOOL_EXECUTION aiTraceId={} reqId={} conversationId={} userKey={} seq={} toolName={} toolDurationMs={} toolOutcome={}",
                aiTraceId, requestId, conversationId, userKey, entry.sequence(), entry.toolName(),
                elapsedMillis(entry.startedAtNanos()), resultOutcome(result));
    }

    synchronized void fail(NativeToolAuditEntry entry, RuntimeException exception) {
        String outcome = AiObservabilitySupport.classifyFailure(exception);
        NativeToolAuditEntry updated = new NativeToolAuditEntry(entry.sequence(), entry.toolName(), entry.arguments(),
                entry.requestedAt(), entry.startedAtNanos(), null, "Tool执行失败，未产生副作用",
                outcome, elapsedMillis(entry.startedAtNanos()));
        replace(entry, updated);
        log.info("AI_TOOL_EXECUTION aiTraceId={} reqId={} conversationId={} userKey={} seq={} toolName={} toolDurationMs={} toolOutcome={}",
                aiTraceId, requestId, conversationId, userKey, entry.sequence(), entry.toolName(),
                elapsedMillis(entry.startedAtNanos()), outcome);
    }

    private void replace(NativeToolAuditEntry oldEntry, NativeToolAuditEntry newEntry) {
        int index = entries.indexOf(oldEntry);
        if (index >= 0) entries.set(index, newEntry);
    }

    List<NativeToolAuditEntry> entries() { return List.copyOf(entries); }

    List<AiToolCallResponse> responses() { return entries.stream().map(this::toResponse).toList(); }

    synchronized List<KnowledgeReference> knowledgeReferences() {
        return List.copyOf(knowledgeReferences);
    }

    List<Object> successfulResults() {
        return entries.stream()
                .filter(entry -> (entry.result() instanceof ReadOnlyToolResult readOnly && readOnly.hasUsableFacts())
                        || AiObservabilitySupport.SUCCESS.equals(entry.outcome())
                        || AiObservabilitySupport.PARTIAL_SUCCESS.equals(entry.outcome()))
                .map(NativeToolAuditEntry::result)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    boolean hasFailure() {
        return entries.stream().anyMatch(entry -> entry.outcome() != null
                && !AiObservabilitySupport.SUCCESS.equals(entry.outcome())
                && !AiObservabilitySupport.PARTIAL_SUCCESS.equals(entry.outcome()));
    }

    void requireKnownProductIds(String toolName, List<Long> productIds) {
        policy.requireKnownProductIds(toolName, productIds);
    }

    void policyRejected(String toolName, String message) {
        log.info("AI_TOOL_POLICY_REJECTED aiTraceId={} reqId={} conversationId={} userKey={} toolName={} reason={}",
                aiTraceId, requestId, conversationId, userKey, toolName, message);
    }

    String toolNames() {
        return entries.stream().map(NativeToolAuditEntry::toolName).reduce((left, right) -> left + "," + right)
                .orElse("NONE");
    }

    String toolOutcomes() {
        return entries.stream().map(entry -> entry.toolName() + "="
                        + (entry.outcome() == null ? AiObservabilitySupport.UNAVAILABLE : entry.outcome()))
                .reduce((left, right) -> left + "," + right).orElse("NONE");
    }

    OrderDraftResponse orderDraft() {
        return entries.stream().map(NativeToolAuditEntry::result)
                .filter(OrderDraftResponse.class::isInstance).map(OrderDraftResponse.class::cast).findFirst().orElse(null);
    }

    private AiToolCallResponse toResponse(NativeToolAuditEntry entry) {
        Object result = entry.result();
        if (result instanceof ReadOnlyToolResult readOnly) {
            return new AiToolCallResponse(entry.toolName(), readOnly.success(), readOnly.message(),
                    readOnly.productIds(), readOnly.total(), readOnly.queriedAt(), entry.arguments(),
                    entry.sequence(), "MODEL_REQUEST", readOnly.resultStatus().name());
        }
        if (result instanceof HybridRagService.RetrievalResult retrieval) {
            return new AiToolCallResponse(entry.toolName(), true, "已完成混合 RAG 检索",
                    retrieval.productIds(), retrieval.references().size(), entry.requestedAt(), entry.arguments(),
                    entry.sequence(), "MODEL_REQUEST", "SUCCESS");
        }
        if (result instanceof CartToolResult cart) {
            List<Long> productIds = cart.cart() == null || cart.cart().items() == null ? List.of()
                    : cart.cart().items().stream().map(CartItemResponse::productId).toList();
            long total = cart.cart() == null || cart.cart().totalQuantity() == null ? 0 : cart.cart().totalQuantity();
            return new AiToolCallResponse(entry.toolName(), cart.success(), cart.message(), productIds,
                    total, entry.requestedAt(), entry.arguments(), entry.sequence(), "MODEL_REQUEST");
        }
        if (result instanceof OrderQueryToolResult query) {
            List<Long> productIds = query.order() == null || query.order().items() == null ? List.of()
                    : query.order().items().stream().map(item -> item.productId()).toList();
            long total = "list".equals(query.operation()) ? query.total() : productIds.size();
            return new AiToolCallResponse(entry.toolName(), query.success(), query.message(), productIds,
                    total, entry.requestedAt(), entry.arguments(), entry.sequence(), "MODEL_REQUEST");
        }
        if (result instanceof OrderDraftResponse draft) {
            long total = draft.items() == null ? 0 : draft.items().size();
            return new AiToolCallResponse(entry.toolName(), true, "已生成订单预览", List.of(), total,
                    entry.requestedAt(), entry.arguments(), entry.sequence(), "MODEL_REQUEST");
        }
        return new AiToolCallResponse(entry.toolName(), false,
                entry.failureMessage() == null ? "Tool执行失败" : entry.failureMessage(), List.of(), 0,
                entry.requestedAt(), entry.arguments(), entry.sequence(), "MODEL_REQUEST");
    }

    static Object parseToolResult(String toolName, String result) {
        if (result == null || result.isBlank()) return null;
        try {
            return switch (toolName) {
                case "ProductDetailTool", "InventoryTool" ->
                        JSON.readValue(result, ReadOnlyToolResult.class);
                case "HybridRagTool" -> JSON.readValue(result, HybridRagService.RetrievalResult.class);
                case "CartTool" -> JSON.readValue(result, CartToolResult.class);
                case "OrderQueryTool" -> JSON.readValue(result, OrderQueryToolResult.class);
                case "OrderPreviewTool" -> JSON.readValue(result, OrderDraftResponse.class);
                default -> null;
            };
        } catch (Exception exception) {
            log.warn("AI Tool结果解析失败，保留执行记录 tool={}", toolName);
            return null;
        }
    }

    private static String resultOutcome(Object result) {
        if (result instanceof ReadOnlyToolResult readOnly) {
            return switch (readOnly.resultStatus()) {
                case SUCCESS -> AiObservabilitySupport.SUCCESS;
                case PARTIAL_SUCCESS -> AiObservabilitySupport.PARTIAL_SUCCESS;
                case BUSINESS_FAILURE -> AiObservabilitySupport.BUSINESS_FAILURE;
                case SYSTEM_FAILURE -> AiObservabilitySupport.SYSTEM_FAILURE;
            };
        }
        if (result instanceof HybridRagService.RetrievalResult) return AiObservabilitySupport.SUCCESS;
        if (result instanceof CartToolResult cart) return cart.success() ? AiObservabilitySupport.SUCCESS : AiObservabilitySupport.BUSINESS_FAILURE;
        if (result instanceof OrderQueryToolResult query) return query.success() ? AiObservabilitySupport.SUCCESS : AiObservabilitySupport.BUSINESS_FAILURE;
        if (result instanceof OrderDraftResponse) return AiObservabilitySupport.SUCCESS;
        return AiObservabilitySupport.SYSTEM_FAILURE;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
