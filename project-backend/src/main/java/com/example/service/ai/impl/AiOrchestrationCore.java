package com.example.service.ai.impl;

import com.example.ai.memory.ConversationMemoryService;
import com.example.ai.memory.ConversationMessage;
import com.example.ai.rag.HybridRagService;
import com.example.ai.rag.KnowledgeReference;
import com.example.ai.rag.RagUnavailableException;
import com.example.ai.tool.CartTool;
import com.example.ai.tool.InventoryTool;
import com.example.ai.tool.OrderQueryTool;
import com.example.ai.tool.ProductDetailTool;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AiChatResultStatus;
import com.example.dto.ai.Response.AiToolCallResponse;
import com.example.exception.BusinessException;
import com.example.exception.UnauthorizedException;
import com.example.service.order.OrderDraftService;
import com.example.service.ai.AgentRunService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 同步与流式共用的模型编排核心。
 * 核心负责两件事：前半段「读历史 → 建审计 → 注册 Tool → 构建模型请求」和后半段
 * 「事实校验 → 组装响应 → 写记忆」，但**不决定模型怎么执行**。调用方拿到
 * {@link #buildSpec} 产出的请求后，同步路径用 {@code .call()}、流式路径用 {@code .stream()}，
 * 两条路径共用同一套提示词、Tool 注册、身份边界和事实校验。
 * 流式接口不得另建一条绕过事实校验的链路。抽成核心后，流式驱动没有机会自己拼提示词或自己调 Tool。
 */
@Component
public class AiOrchestrationCore {
    private static final Logger log = LoggerFactory.getLogger(AiOrchestrationCore.class);
    private static final int MAX_CHAT_MESSAGE_LENGTH = 2000;

    @Resource
    private AgentRunService agentRunService;

    @Resource
    private ProductDetailTool productDetailTool;
    @Resource
    private InventoryTool inventoryTool;
    @Resource
    private CartTool cartTool;
    @Resource
    private OrderQueryTool orderQueryTool;
    @Resource
    private OrderDraftService orderDraftService;
    @Resource
    private ConversationMemoryService conversationMemoryService;
    @Resource
    private HybridRagService hybridRagService;
    @Resource
    private ChatClient chatClient;
    @Resource
    private AiPromptBuilder promptBuilder = new AiPromptBuilder();
    @Resource
    private AiResponseAssembler responseAssembler = new AiResponseAssembler();

    /** 单轮编排上下文；由 {@link #prepare} 产出，同步与流式驱动共用同一份。 */
    public record Prepared(Long userId,
                           AiChatRequest request,
                           String aiTraceId,
                           String requestId,
                           String userKey,
                           List<ConversationMessage> history,
                           NativeToolAudit audit,
                           AiChatResponse replay) {
    }

    /** 模型执行的观测值。流式下必须只取最后一个 chunk 的值，中间 chunk 的 usage 是假的 0。 */
    public record ModelMetrics(long durationMs, String tokenUsage, String providerHttpStatus, String finishReason) {
        public static ModelMetrics unavailable(long durationMs) {
            return new ModelMetrics(durationMs, AiObservabilitySupport.UNAVAILABLE,
                    AiObservabilitySupport.UNAVAILABLE, AiObservabilitySupport.UNAVAILABLE);
        }
    }

    /**
     * 身份与入参校验、读历史、建审计。
     *
     * <p>校验异常向外抛出，不转成模型错误 —— 这与重构前的行为一致：参数不合法要返回 400，
     * 不能被当成模型失败而返回 200 + MODEL_ERROR。</p>
     */
    public Prepared prepare(Long userId, AiChatRequest request) {
        requireUserId(userId);
        AiChatRequest normalized = normalize(request);
        AgentRunService.Start started = agentRunService.begin(userId, normalized);
        String aiTraceId = started.runId();
        String userKey = AiObservabilitySupport.userKey(userId);
        String requestId = MDC.get("reqId");
        log.info("AI_MODEL_REQUEST aiTraceId={} reqId={} conversationId={} userKey={}",
                aiTraceId, requestId, normalized.getConversationId(), userKey);

        List<ConversationMessage> history = started.replay() != null ? List.of() : readHistory(userId, normalized.getConversationId(),
                aiTraceId, requestId, userKey);
        NativeToolAudit audit = new NativeToolAudit(aiTraceId, requestId, normalized.getConversationId(), userKey,
                userId, agentRunService);
        return new Prepared(userId, normalized, aiTraceId, requestId, userKey, history, audit, started.replay());
    }

    public void interrupt(Prepared prepared) {
        agentRunService.interrupt(prepared.userId(), prepared.aiTraceId(), "CLIENT_DISCONNECTED");
    }

    public void heartbeat(Prepared prepared) {
        agentRunService.heartbeat(prepared.userId(), prepared.aiTraceId());
    }

    /**
     * 构建模型请求：系统提示词、用户提示词、Tool 注册、ToolContext。
     *
     * <p>调用方决定用 {@code .call()} 还是 {@code .stream()} 执行，核心不关心。</p>
     */
    public ChatClient.ChatClientRequestSpec buildSpec(Prepared prepared) {
        ToolCallback[] callbacks = callbacks(prepared);
        return chatClient.prompt()
                .system(promptBuilder.systemPrompt())
                .user(promptBuilder.userPrompt(prepared.request().getMessage(), prepared.history()))
                .tools((Object[]) callbacks)
                .toolContext(Map.of("userId", prepared.userId(),
                        "conversationId", prepared.request().getConversationId()));
    }

    /** 事实校验 → 组装响应 → 写记忆 → 收尾日志。 */
    public AiChatResponse finish(Prepared prepared, String rawMessage, ModelMetrics metrics) {
        AiChatRequest request = prepared.request();
        NativeToolAudit audit = prepared.audit();
        List<KnowledgeReference> knowledgeReferences = audit.knowledgeReferences();
        AiGroundingValidator.GroundingDecision grounding = AiGroundingValidator.validate(
                rawMessage, request.getMessage(), audit.groundingResults(), knowledgeReferences);
        AiChatResultStatus resultStatus = grounding.accepted()
                ? AiChatResultStatus.SUCCESS : AiChatResultStatus.GROUNDING_REJECTED;
        if (!grounding.accepted()) {
            log.warn("AI_GROUNDING_REJECTED aiTraceId={} reqId={} conversationId={} userKey={} failureType={}",
                    prepared.aiTraceId(), prepared.requestId(), request.getConversationId(), prepared.userKey(),
                    grounding.failureType());
        }
        AiChatResponse response = responseAssembler.assemble(
                request, grounding.message(), resultStatus, audit, knowledgeReferences);
        agentRunService.finish(prepared.userId(), prepared.aiTraceId(), response);
        remember(prepared, response);
        logFinalReply(prepared, response, metrics);
        return response;
    }

    /** 模型返回空文本：按既有约定返回 EMPTY_RESPONSE，不写记忆中也不做事实校验。 */
    public AiChatResponse emptyReply(Prepared prepared, ModelMetrics metrics) {
        return failure(prepared, AiChatResultStatus.EMPTY_RESPONSE, metrics);
    }

    /** 模型或 Tool 失败：按既有分类映射到稳定错误状态。 */
    public AiChatResponse fail(Prepared prepared, Throwable cause, ModelMetrics metrics) {
        AiChatResultStatus resultStatus = failureStatus(cause, prepared.audit());
        ModelMetrics failed = new ModelMetrics(metrics.durationMs(), metrics.tokenUsage(),
                AiObservabilitySupport.providerHttpStatus(cause), metrics.finishReason());
        log.warn("AI_MODEL_FAILURE aiTraceId={} reqId={} conversationId={} userKey={} modelDurationMs={} providerHttpStatus={} resultStatus={} exceptionType={}",
                prepared.aiTraceId(), prepared.requestId(), prepared.request().getConversationId(), prepared.userKey(),
                failed.durationMs(), failed.providerHttpStatus(), resultStatus, cause.getClass().getSimpleName());
        return failure(prepared, resultStatus, failed);
    }

    private AiChatResponse failure(Prepared prepared, AiChatResultStatus resultStatus, ModelMetrics metrics) {
        AiChatResponse response = responseAssembler.assemble(prepared.request(),
                responseAssembler.failureMessage(resultStatus), resultStatus,
                prepared.audit(), prepared.audit().knowledgeReferences());
        agentRunService.finish(prepared.userId(), prepared.aiTraceId(), response);
        logFinalReply(prepared, response, metrics);
        return response;
    }

    private ToolCallback[] callbacks(Prepared prepared) {
        // 业务幂等标识与任务标识独立；重发请求在 prepare 阶段回放或拒绝。
        NativeToolSet toolSet = new NativeToolSet(prepared.userId(), prepared.request().getClientRequestId(), prepared.audit(),
                productDetailTool, inventoryTool,
                cartTool, orderQueryTool, orderDraftService, hybridRagService);
        return Arrays.stream(ToolCallbacks.from(toolSet))
                .map(callback -> new AuditedToolCallback(callback, prepared.audit()))
                .toArray(ToolCallback[]::new);
    }

    private void logFinalReply(Prepared prepared, AiChatResponse response, ModelMetrics metrics) {
        log.info("AI_FINAL_REPLY aiTraceId={} reqId={} conversationId={} userKey={} modelDurationMs={} tokenUsage={} providerHttpStatus={} finishReason={} toolCount={} toolNames={} toolOutcomes={} resultStatus={}",
                prepared.aiTraceId(), prepared.requestId(), prepared.request().getConversationId(), prepared.userKey(),
                metrics.durationMs(), metrics.tokenUsage(), metrics.providerHttpStatus(), metrics.finishReason(),
                response.toolCalls().size(), prepared.audit().toolNames(), prepared.audit().toolOutcomes(),
                response.resultStatus());
    }

    private AiChatResultStatus failureStatus(Throwable exception, NativeToolAudit audit) {
        if (hasCause(exception, RagUnavailableException.class)) {
            return AiChatResultStatus.RAG_UNAVAILABLE;
        }
        if (AiObservabilitySupport.TIMEOUT.equals(AiObservabilitySupport.classifyFailure(exception))) {
            return AiChatResultStatus.MODEL_TIMEOUT;
        }
        return audit.hasFailure() || exception instanceof BusinessException
                ? AiChatResultStatus.TOOL_ERROR : AiChatResultStatus.MODEL_ERROR;
    }

    private boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<ConversationMessage> readHistory(Long userId,
                                                  String conversationId,
                                                  String aiTraceId,
                                                  String requestId,
                                                  String userKey) {
        long startedAt = System.nanoTime();
        try {
            return conversationMemoryService.getHistory(userId, conversationId);
        } catch (RuntimeException exception) {
            log.warn("AI_MEMORY_READ_FAILED aiTraceId={} reqId={} conversationId={} userKey={} memoryDurationMs={} exceptionType={}",
                    aiTraceId, requestId, conversationId, userKey, elapsedMillis(startedAt),
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    private void remember(Prepared prepared, AiChatResponse response) {
        List<AiToolCallResponse> toolCalls = response.toolCalls();
        String toolSummary = toolCalls.stream()
                .map(call -> call.toolName() + "(" + (call.success() ? "成功" : "失败") + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("无 Tool 调用");
        try {
            conversationMemoryService.append(prepared.userId(), prepared.request().getConversationId(), List.of(
                    new ConversationMessage("user", prepared.request().getMessage()),
                    new ConversationMessage("assistant", response.message() + "\nTool摘要：" + toolSummary)));
        } catch (RuntimeException exception) {
            log.warn("写入会话记忆失败，不影响本轮响应 exceptionType={}", exception.getClass().getSimpleName());
        }
    }

    private AiChatRequest normalize(AiChatRequest request) {
        if (request == null) throw new BusinessException("聊天请求不能为空");
        String clientRequestId = request.getClientRequestId();
        if (clientRequestId == null || clientRequestId.isBlank()) throw new BusinessException("请求标识不能为空");
        if (!clientRequestId.matches("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")) throw new BusinessException("请求标识格式不正确");
        String conversationId = StringUtils.trimToNull(request.getConversationId());
        String message = StringUtils.trimToNull(request.getMessage());
        if (conversationId == null) throw new BusinessException("会话ID不能为空");
        if (!conversationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) throw new BusinessException("会话ID格式不正确");
        if (message == null) throw new BusinessException("消息内容不能为空");
        if (message.length() > MAX_CHAT_MESSAGE_LENGTH) throw new BusinessException("消息内容不能超过" + MAX_CHAT_MESSAGE_LENGTH + "个字符");
        if (message.chars().anyMatch(Character::isISOControl)) throw new BusinessException("消息内容不能包含控制字符");
        AiChatRequest normalized = new AiChatRequest();
        normalized.setClientRequestId(clientRequestId);
        normalized.setConversationId(conversationId);
        normalized.setMessage(message);
        return normalized;
    }

    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) throw new UnauthorizedException("未登录或登录已失效");
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
