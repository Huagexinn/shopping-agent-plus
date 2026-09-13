package com.example.filter;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为每个 HTTP 请求生成服务端追踪 ID，供请求日志和 AI 审计日志关联。 */
@Component
@Order(-110)
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final String REQUEST_ID_KEY = "reqId";
    private static final String RESPONSE_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(RESPONSE_HEADER, requestId);
        log.info("HTTP_REQUEST_START reqId={} method={} path={}", requestId,
                request.getMethod(), request.getRequestURI());
        try {
            filterChain.doFilter(request, response);
        } finally {
            logCompletion(request, response, requestId, startedAt);
            // 异步线程不继承 MDC，所以 reqId 必须已经在 Servlet 线程被业务代码取走
            MDC.remove(REQUEST_ID_KEY);
        }
    }

    /**
     * 记录请求结束。
     *
     * <p>SSE 这类异步请求在 {@code doFilter} 返回时**流才刚刚开始**，此时统计到的是「首字节耗时」而不是
     * 整段流的耗时（实测 SSE 请求会打出 durationMs=6，而实际推送持续数秒）。因此异步请求不在 Servlet
     * 线程记结束，而是注册 {@link AsyncListener}，等异步周期真正结束再记，durationMs 才是真实耗时。</p>
     *
     * <p>{@code OncePerRequestFilter} 默认跳过 async dispatch，异步线程不会重跑本过滤器，所以
     * 结束日志只能靠监听器补。</p>
     */
    private void logCompletion(HttpServletRequest request, HttpServletResponse response,
                               String requestId, long startedAt) {
        if (!request.isAsyncStarted()) {
            logEnd(requestId, response.getStatus(), startedAt);
            return;
        }
        log.info("HTTP_REQUEST_ASYNC_START reqId={} method={} path={} firstByteDurationMs={}",
                requestId, request.getMethod(), request.getRequestURI(), elapsedMillis(startedAt));
        // onError 之后容器仍可能再回调 onComplete，这里保证结束日志只记一次，否则同一个 reqId 会出现两行
        AtomicBoolean ended = new AtomicBoolean(false);
        AsyncListener listener = new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                logEndOnce(ended, requestId, statusOf(event, response), startedAt);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                logEndOnce(ended, requestId, statusOf(event, response), startedAt);
            }

            @Override
            public void onError(AsyncEvent event) {
                logEndOnce(ended, requestId, statusOf(event, response), startedAt);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 本项目不使用嵌套异步；若将来启用，需要在 onStartAsync 里重新注册监听器
            }
        };
        try {
            request.getAsyncContext().addListener(listener);
        } catch (IllegalStateException exception) {
            // 异步周期在注册前就结束了，退化成在 Servlet 线程记一次
            logEnd(requestId, response.getStatus(), startedAt);
        }
    }

    private void logEndOnce(AtomicBoolean ended, String requestId, int status, long startedAt) {
        if (ended.compareAndSet(false, true)) {
            logEnd(requestId, status, startedAt);
        }
    }

    private void logEnd(String requestId, int status, long startedAt) {
        log.info("HTTP_REQUEST_END reqId={} status={} durationMs={}", requestId, status, elapsedMillis(startedAt));
    }

    private int statusOf(AsyncEvent event, HttpServletResponse response) {
        if (event != null && event.getSuppliedResponse() instanceof HttpServletResponse asyncResponse) {
            return asyncResponse.getStatus();
        }
        return response.getStatus();
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
