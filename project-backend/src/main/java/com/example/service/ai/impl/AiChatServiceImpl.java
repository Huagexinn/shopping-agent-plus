package com.example.service.ai.impl;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.service.ai.AiChatService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * AI 聊天同步驱动。
 *
 * <p>编排逻辑（读历史、Tool 注册、提示词、事实校验、组装响应、写记忆）全部在
 * {@link AiOrchestrationCore}；本类只负责用同步方式执行核心构建好的请求，并把失败收敛成稳定响应。
 * 流式驱动将复用同一个核心，因此必须先做这次抽取，再动流式。</p>
 */
@Service
public class AiChatServiceImpl implements AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    @Resource
    private AiOrchestrationCore core = new AiOrchestrationCore();
    @Resource
    private AiModelGateway modelGateway = new AiModelGateway();

    @Override
    public AiChatResponse chat(Long userId, AiChatRequest request) {
        AiOrchestrationCore.Prepared prepared = core.prepare(userId, request);
        if (prepared.replay() != null) return prepared.replay();
        long modelStartedAt = System.nanoTime();
        try {
            ChatClientResponse clientResponse = modelGateway.call(core.buildSpec(prepared));
            long modelDurationMs = elapsedMillis(modelStartedAt);
            ChatResponse chatResponse = clientResponse == null ? null : clientResponse.chatResponse();
            AiOrchestrationCore.ModelMetrics metrics = new AiOrchestrationCore.ModelMetrics(
                    modelDurationMs,
                    AiObservabilitySupport.tokenUsage(chatResponse),
                    AiObservabilitySupport.providerHttpStatus(chatResponse),
                    AiObservabilitySupport.finishReason(chatResponse));
            String message = chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null
                    ? null : chatResponse.getResult().getOutput().getText();
            log.info("AI_MODEL_RESPONSE aiTraceId={} reqId={} conversationId={} userKey={} modelDurationMs={} tokenUsage={} providerHttpStatus={} finishReason={} responseEmpty={}",
                    prepared.aiTraceId(), prepared.requestId(), prepared.request().getConversationId(), prepared.userKey(),
                    metrics.durationMs(), metrics.tokenUsage(), metrics.providerHttpStatus(), metrics.finishReason(),
                    message == null || message.isBlank());
            if (message == null || message.isBlank()) {
                log.warn("AI_MODEL_EMPTY_RESPONSE aiTraceId={} reqId={} conversationId={} userKey={} modelDurationMs={} providerHttpStatus={} finishReason={}",
                        prepared.aiTraceId(), prepared.requestId(), prepared.request().getConversationId(),
                        prepared.userKey(), metrics.durationMs(), metrics.providerHttpStatus(), metrics.finishReason());
                return core.emptyReply(prepared, metrics);
            }
            return core.finish(prepared, message, metrics);
        } catch (RuntimeException exception) {
            Throwable cause = exception instanceof java.util.concurrent.CompletionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            return core.fail(prepared, cause,
                    AiOrchestrationCore.ModelMetrics.unavailable(elapsedMillis(modelStartedAt)));
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
