package com.example.service.ai.impl;

import com.example.ai.model.response.ReadOnlyProductSnapshot;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.rag.KnowledgeReference;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AiChatResultStatus;
import com.example.dto.ai.Response.AiKnowledgeReferenceResponse;
import com.example.dto.ai.Response.AiReferenceResponse;
import com.example.dto.ai.Response.AiToolCallResponse;
import com.example.dto.order.Response.OrderDraftResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将模型文本、Tool 审计、商品引用和订单预览组装为稳定响应。 */
@Component
public class AiResponseAssembler {
    private static final String STATUS_CONFIRM_REQUIRED = "CONFIRM_REQUIRED";

    public AiChatResponse assemble(AiChatRequest request,
                                    String message,
                                    AiChatResultStatus resultStatus,
                                    NativeToolAudit audit,
                                    List<KnowledgeReference> knowledgeReferences) {
        List<AiToolCallResponse> toolCalls = audit.responses();
        List<AiReferenceResponse> references = new ArrayList<>();
        for (NativeToolAuditEntry entry : audit.entries()) {
            if (entry.result() instanceof ReadOnlyToolResult result) {
                references.addAll(buildReferences(result.products(), result.toolName()));
            }
        }
        Map<Long, AiReferenceResponse> distinctReferences =
                new LinkedHashMap<>();

        for (AiReferenceResponse reference : references) {

            Long productId = reference.productId();

            AiReferenceResponse existing =
                    distinctReferences.get(productId);

            // 第一次出现，直接保存
            if (existing == null) {
                distinctReferences.put(productId, reference);
                continue;
            }

            // 合并多个 Tool 的事实
            List<String> mergedFacts = new ArrayList<>(
                    existing.facts()
            );

            for (String fact : reference.facts()) {
                if (!mergedFacts.contains(fact)) {
                    mergedFacts.add(fact);
                }
            }

            // 合并证据来源
            String mergedSource = existing.source();

            if (!mergedSource.contains(reference.source())) {
                mergedSource += "," + reference.source();
            }

            // 保留已有商品信息，合并事实
            AiReferenceResponse merged = new AiReferenceResponse(
                    productId,
                    existing.title() != null
                            ? existing.title()
                            : reference.title(),
                    mergedSource,
                    mergedFacts
            );

            distinctReferences.put(productId, merged);
        }
        OrderDraftResponse draft = audit.orderDraft();
        String status = draft == null ? null : STATUS_CONFIRM_REQUIRED;
        return new AiChatResponse(resultStatus, request.getConversationId(),
                message == null ? "" : message.trim(), toolCalls,
                List.copyOf(distinctReferences.values()), toKnowledgeReferences(knowledgeReferences), status, draft);
    }

    public String failureMessage(AiChatResultStatus resultStatus) {
        return switch (resultStatus) {
            case MODEL_TIMEOUT -> "AI 服务响应超时，请查询执行结果；重新购买前请先核对购物车或订单。";
            case EMPTY_RESPONSE -> "AI 未返回有效回复，请查询执行结果，已完成的操作不会自动撤销。";
            case TOOL_ERROR -> "AI 工具调用未正常完成，请查询执行结果并核对购物车或订单。";
            case RAG_UNAVAILABLE -> "商城知识检索暂时不可用，请先查看本次执行结果。";
            case GROUNDING_REJECTED -> AiGroundingValidator.REJECTION_MESSAGE;
            case MODEL_ERROR -> "AI 服务暂时不可用，请查询执行结果，已完成的操作不会自动撤销。";
            case SUCCESS -> "";
        };
    }

    private List<AiKnowledgeReferenceResponse> toKnowledgeReferences(List<KnowledgeReference> references) {
        return (references == null ? List.<KnowledgeReference>of() : references).stream()
                .map(reference -> new AiKnowledgeReferenceResponse(
                        reference.documentId(), reference.title(), reference.sourceType(),
                        reference.content(), reference.productId()))
                .toList();
    }

    private List<AiReferenceResponse> buildReferences(List<ReadOnlyProductSnapshot> snapshots, String source) {
        Map<Long, AiReferenceResponse> references = new LinkedHashMap<>();
        for (ReadOnlyProductSnapshot snapshot : snapshots) {
            if (snapshot.productId() == null
                    || !snapshot.matched()
                    || references.containsKey(snapshot.productId())) {
                continue;
            }
            references.put(snapshot.productId(), new AiReferenceResponse(
                    snapshot.productId(), snapshot.title(), source, buildFacts(snapshot)));
        }
        return List.copyOf(references.values());
    }

    private List<String> buildFacts(ReadOnlyProductSnapshot snapshot) {
        List<String> facts = new ArrayList<>(snapshot.matchedFacts());
        if (facts.isEmpty()) {
            if (snapshot.price() != null) facts.add("价格=" + snapshot.price());
            if (snapshot.ramGb() != null) facts.add("内存=" + snapshot.ramGb() + "GB");
            if (snapshot.storageGb() != null) facts.add("存储=" + snapshot.storageGb() + "GB");
            if (snapshot.stock() != null) facts.add("库存=" + snapshot.stock());
        }
        if (snapshot.reason() != null && facts.size() < 5) {
            facts.add("说明=" + snapshot.reason());
        }
        return facts;
    }
}
