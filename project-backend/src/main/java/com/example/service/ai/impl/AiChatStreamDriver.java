package com.example.service.ai.impl;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AiChatResultStatus;
import com.example.dto.ai.Response.AiStreamErrorResponse;
import com.example.dto.ai.Response.AiStreamEventResponse;
import com.example.dto.ai.Response.AiStreamMetaResponse;
import com.example.dto.ai.Response.AiStreamReplaceResponse;
import com.example.dto.ai.Response.AiStreamTextDeltaResponse;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 聊天的流式驱动。
 *
 * <p><b>本类不含任何编排逻辑</b>：提示词、Tool 注册、身份边界、事实校验、响应组装、记忆写入全部由
 * {@link AiOrchestrationCore} 负责。本类只做四件事 —— 把模型 chunk 交给
 * {@link AiStreamingGroundingGuard} 按完整句子结算、把结算通过的片段作为 {@code text_delta} 发出、
 * 校验不通过时中断生成并发 {@code replace}、把取消传回上游。</p>
 *
 * <p>这是 AGENTS.md 第 8 节第 10 条要求的形态：流式不是另一条链路，而是同一个核心的另一个驱动。
 * 前端拿到的每一段文本都先经过 {@link AiGroundingValidator}，不存在裸推模型输出的路径。</p>
 */
@Service
public class AiChatStreamDriver {
    private static final Logger log = LoggerFactory.getLogger(AiChatStreamDriver.class);
    /** 流式收尾日志的统一格式；正常结束用 INFO，中断/取消/失败用 WARN，靠级别区分严重性。 */
    private static final String STREAM_END_FORMAT =
            "AI_STREAM_END aiTraceId={} reqId={} conversationId={} userKey={} outcome={} detail={} "
                    + "resultStatus={} deltaCount={} renderedChars={} durationMs={}";

    @Resource
    private AiOrchestrationCore core = new AiOrchestrationCore();
    @Resource
    private AiModelGateway modelGateway = new AiModelGateway();

    /**
     * 以 SSE 事件流的形式执行一轮 AI 对话。
     *
     * <p>身份与入参校验**在流开始之前**同步执行，异常按同步接口的语义抛出（400 / 401），
     * 不会变成流里的 {@code error} 事件 —— 校验失败时响应头还没提交，能给出正确的 HTTP 状态码。</p>
     */
    public Flux<AiStreamEventResponse> stream(Long userId, AiChatRequest request) {
        AiOrchestrationCore.Prepared prepared = core.prepare(userId, request);
        return Flux.create(sink -> new StreamRun(sink, prepared).start());
    }

    /** 单轮流的运行状态；每轮一个实例，避免跨请求共享可变状态。 */
    private final class StreamRun {
        private final FluxSink<AiStreamEventResponse> sink;
        private final AiOrchestrationCore.Prepared prepared;
        private final AiStreamingGroundingGuard guard = new AiStreamingGroundingGuard();
        private final long startedAt = System.nanoTime();
        /** spike 证实 usage / finishReason 只在最后一个 chunk 有效，所以只保留最后一个。 */
        private final AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();
        private final AtomicReference<Disposable> upstream = new AtomicReference<>();
        /** 保证 replace / final / error / complete 只发生一次，中断后的残余 chunk 一律丢弃。 */
        private final AtomicBoolean terminated = new AtomicBoolean(false);
        private final AtomicInteger deltaCount = new AtomicInteger();

        private StreamRun(FluxSink<AiStreamEventResponse> sink, AiOrchestrationCore.Prepared prepared) {
            this.sink = sink;
            this.prepared = prepared;
        }

        private void start() {
            sink.next(AiStreamEventResponse.of("meta", new AiStreamMetaResponse(
                    prepared.request().getConversationId(), prepared.aiTraceId(), prepared.requestId())));
            log.info("AI_STREAM_START aiTraceId={} reqId={} conversationId={} userKey={}",
                    prepared.aiTraceId(), prepared.requestId(), prepared.request().getConversationId(),
                    prepared.userKey());

            ChatClient.ChatClientRequestSpec spec;
            try {
                spec = core.buildSpec(prepared);
            } catch (RuntimeException exception) {
                if (terminated.compareAndSet(false, true)) {
                    AiChatResponse response = core.fail(prepared, exception,
                            AiOrchestrationCore.ModelMetrics.unavailable(0L));
                    emitFailure(response);
                    logEnd("failed", response, exception.getClass().getSimpleName());
                    sink.complete();
                }
                return;
            }

            Disposable subscription = modelGateway.stream(spec)
                    .subscribe(this::onChunk, this::onError, this::onComplete);
            upstream.set(subscription);
            // 中断可能发生在 subscribe 返回之前（同步 Flux 的第一次 onNext 就会触发校验），
            // 那时 upstream 还是 null，dispose 会被跳过。这里补一次，确保生成一定被掐断。
            if (terminated.get()) {
                subscription.dispose();
            }
            sink.onCancel(this::cancel);
            sink.onDispose(this::cancel);
        }

        private void onChunk(ChatClientResponse clientResponse) {
            if (terminated.get()) {
                return;
            }
            ChatResponse chatResponse = clientResponse == null ? null : clientResponse.chatResponse();
            if (chatResponse != null) {
                lastChunk.set(chatResponse);
            }
            String text = textOf(chatResponse);
            if (text == null || text.isEmpty()) {
                return;
            }
            AiStreamingGroundingGuard.Settlement settlement = guard.accept(text,
                    prepared.request().getMessage(),
                    prepared.audit().successfulResults(),
                    prepared.audit().knowledgeReferences());
            if (settlement.rejected()) {
                interrupt(settlement.failureType());
                return;
            }
            if (!settlement.delta().isEmpty()) {
                deltaCount.incrementAndGet();
                sink.next(AiStreamEventResponse.of("text_delta", new AiStreamTextDeltaResponse(settlement.delta())));
            }
        }

        /**
         * 校验中断：先掐断模型生成，再整体替换已渲染内容，最后仍发 {@code final}。
         *
         * <p>发 {@code final} 而不是直接结束，是为了让前端不管走哪条路径都能拿到完整状态 —— 同步接口
         * 在事实校验拒绝时也是返回完整的 {@code AiChatResponse}。这里 {@code core.finish()} 对全文再跑
         * 一次校验必然同样被拒（被拒的片段是全文的子串），因此 {@code replace} 的文本与非流式逐字一致。</p>
         */
        private void interrupt(String failureType) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            Disposable subscription = upstream.get();
            if (subscription != null) {
                subscription.dispose();
            }
            AiChatResponse response = core.finish(prepared, guard.text(), metrics());
            sink.next(AiStreamEventResponse.of("replace",
                    new AiStreamReplaceResponse(response.message(), failureType)));
            sink.next(AiStreamEventResponse.of("final", response));
            logEnd("interrupted", response, failureType);
            sink.complete();
        }

        private void onError(Throwable throwable) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            AiChatResponse response = core.fail(prepared, unwrap(throwable), metrics());
            emitFailure(response);
            logEnd("failed", response, response.resultStatus().name());
            sink.complete();
        }

        private void onComplete() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            String rawMessage = guard.text();
            if (rawMessage.isBlank()) {
                AiChatResponse response = core.emptyReply(prepared, metrics());
                emitFailure(response);
                logEnd("empty", response, AiChatResultStatus.EMPTY_RESPONSE.name());
            } else {
                // 直接结束而没有触发中断时，final 里的 message 是权威文本：正常时逐字等于流式拼接结果，
                // 事实校验拒绝时就是替换文本。前端统一以 final.message 为准，不需要为两种情况分叉。
                AiChatResponse response = core.finish(prepared, rawMessage, metrics());
                sink.next(AiStreamEventResponse.of("final", response));
                logEnd("completed", response, "NONE");
            }
            sink.complete();
        }

        /**
         * 客户端断开或流被取消。
         *
         * <p>用 CAS 判断：只有「还没有任何终态事件」时才算真正的取消。正常结束后
         * {@code onDispose} 也会触发，那时 CAS 失败，不会打出误导性的取消日志。</p>
         */
        private void cancel() {
            if (terminated.compareAndSet(false, true)) {
                log.warn("AI_STREAM_CANCELLED aiTraceId={} reqId={} conversationId={} userKey={} deltaCount={} renderedChars={} durationMs={}",
                        prepared.aiTraceId(), prepared.requestId(), prepared.request().getConversationId(),
                        prepared.userKey(), deltaCount.get(), guard.text().length(), elapsedMillis());
            }
            Disposable subscription = upstream.get();
            if (subscription != null) {
                subscription.dispose();
            }
        }

        private void logEnd(String outcome, AiChatResponse response, String detail) {
            Object[] arguments = {prepared.aiTraceId(), prepared.requestId(),
                    prepared.request().getConversationId(), prepared.userKey(), outcome, detail,
                    response == null ? AiObservabilitySupport.UNAVAILABLE : response.resultStatus().name(),
                    deltaCount.get(), guard.text().length(), elapsedMillis()};
            if ("completed".equals(outcome)) {
                log.info(STREAM_END_FORMAT, arguments);
            } else {
                log.warn(STREAM_END_FORMAT, arguments);
            }
        }

        private long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        }

        private void emitFailure(AiChatResponse response) {
            sink.next(AiStreamEventResponse.of("error", new AiStreamErrorResponse(
                    response.resultStatus().code(), response.message(), response.resultStatus())));
        }

        private AiOrchestrationCore.ModelMetrics metrics() {
            ChatResponse chatResponse = lastChunk.get();
            return new AiOrchestrationCore.ModelMetrics(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                    AiObservabilitySupport.tokenUsage(chatResponse),
                    AiObservabilitySupport.providerHttpStatus(chatResponse),
                    AiObservabilitySupport.finishReason(chatResponse));
        }
    }

    private static String textOf(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
    }
}
