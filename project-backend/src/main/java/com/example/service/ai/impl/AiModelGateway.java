package com.example.service.ai.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 模型调用驱动：执行 {@link AiOrchestrationCore#buildSpec} 构建好的请求，并统一超时与异常传播。
 *
 * <p>本类不拼提示词、不注册 Tool —— 那是核心的职责。同步用 {@link #call}，流式用 {@link #stream}，
 * 两者执行的是同一份请求构建结果。</p>
 */
@Component
public class AiModelGateway {
    private static final long MODEL_TIMEOUT_SECONDS = 30L;

    public ChatClientResponse call(ChatClient.ChatClientRequestSpec spec) {
        CompletableFuture<ChatClientResponse> call = CompletableFuture.supplyAsync(() -> spec.call().chatClientResponse());
        try {
            return call.get(MODEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            call.cancel(true);
            throw new java.util.concurrent.CompletionException(exception);
        } catch (InterruptedException exception) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new java.util.concurrent.CompletionException(exception.getCause() == null
                    ? exception : exception.getCause());
        }
    }

    /**
     * 流式执行：返回模型 chunk 流。
     *
     * <p>{@code Flux.timeout(Duration)} 是**相邻两个 chunk 之间**的空闲超时，不是整段流的总时长 ——
     * 总时长限制会把长回复直接掐断，语义上也不该有。空闲超过 30 秒没有新 chunk 才判定超时，
     * 抛出的 {@code java.util.concurrent.TimeoutException} 会被既有失败分类识别为 MODEL_TIMEOUT。</p>
     *
     * <p>返回的 Flux 被取消订阅时，Spring AI 会取消底层 HTTP 请求，客户端断开不会继续消耗模型额度。</p>
     */
    public Flux<ChatClientResponse> stream(ChatClient.ChatClientRequestSpec spec) {
        return spec.stream().chatClientResponse()
                .timeout(Duration.ofSeconds(MODEL_TIMEOUT_SECONDS));
    }
}
