package com.example.service.ai.impl;

import com.example.ai.memory.ConversationMemoryService;
import com.example.ai.memory.ConversationMessage;
import com.example.ai.model.response.CartToolResult;
import com.example.ai.tool.CartTool;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.cart.Request.AddCartItemRequest;
import com.example.dto.cart.Request.UpdateCartItemRequest;
import com.example.dto.cart.Response.CartResponse;
import com.example.exception.ConflictException;
import com.example.service.ai.CartOperationIdempotencyService;
import com.example.service.cart.CartService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性故障回归：真实编排回调与 CartTool，使用内存业务/幂等替身。
 * 第一次调用结果被丢弃，重新创建编排上下文模拟 SSE -> JSON 重发。
 * 本测试验证标识接线，不替代 MySQL 事务/唯一约束集成测试。
 */
class CartRequestRetryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lostResponseThenRetryAddsOnlyOnce() throws Exception {
        Fixture f = new Fixture();
        f.call(1L, "request-001", "add", 1L, 1); // 写入成功，模拟响应丢失
        JsonNode retry = f.call(1L, "request-001", "add", 1L, 1);
        assertTrue(retry.path("success").asBoolean());
        assertEquals(1, f.quantity(1L, 1L), "同一提交重发不能重复加购");
        assertEquals(1, f.writes);
    }

    @Test
    void identicalMessageWithNewRequestIdIsANewPurchase() throws Exception {
        Fixture f = new Fixture();
        f.call(1L, "request-001", "add", 1L, 1);
        f.call(1L, "request-002", "add", 1L, 1);
        assertEquals(2, f.quantity(1L, 1L));
        assertEquals(2, f.writes);
    }

    @Test
    void changedQuantityOnRetryIsRejectedInsteadOfAnotherWrite() throws Exception {
        Fixture f = new Fixture();
        f.call(1L, "request-001", "add", 1L, 1);
        JsonNode retry = f.call(1L, "request-001", "add", 1L, 2);
        assertFalse(retry.path("success").asBoolean());
        assertFalse(retry.path("retryable").asBoolean());
        assertEquals(1, f.quantity(1L, 1L));
        assertEquals(1, f.writes);
    }

    @Test
    void multipleProductsRemainDistinctAndRetryOrderDoesNotMatter() throws Exception {
        Fixture f = new Fixture();
        f.call(1L, "request-001", "add", 1L, 1);
        f.call(1L, "request-001", "add", 2L, 2);
        f.call(1L, "request-001", "add", 2L, 2);
        f.call(1L, "request-001", "add", 1L, 1);
        assertEquals(1, f.quantity(1L, 1L));
        assertEquals(2, f.quantity(1L, 2L));
        assertEquals(2, f.writes);
    }

    @Test
    void sameRequestIdBelongsToItsAuthenticatedUser() throws Exception {
        Fixture f = new Fixture();
        f.call(1L, "request-001", "add", 1L, 1);
        f.call(2L, "request-001", "add", 1L, 2);
        assertEquals(1, f.quantity(1L, 1L));
        assertEquals(2, f.quantity(2L, 1L));
    }

    @Test
    void concurrentRetriesUseOneBusinessIdentity() throws Exception {
        Fixture f = new Fixture();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<JsonNode>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return f.call(1L, "request-001", "add", 1L, 1);
                }));
            }
            start.countDown();
            for (Future<JsonNode> task : tasks) assertTrue(task.get(10, TimeUnit.SECONDS).path("success").asBoolean());
        }
        assertEquals(1, f.quantity(1L, 1L));
        assertEquals(1, f.writes);
    }

    @Test
    void differentOperationTypesDoNotReuseAnAddResult() throws Exception {
        Fixture f = new Fixture();
        f.call(1L, "request-001", "add", 1L, 1);
        JsonNode update = f.call(1L, "request-001", "update", 1L, 3);
        assertTrue(update.path("success").asBoolean());
        assertEquals(3, f.quantity(1L, 1L));
        assertEquals(2, f.writes);
    }

    @Test
    void maximumLengthRequestIdFitsOperationContract() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.call(1L, "a".repeat(64), "add", Long.MAX_VALUE, 1).path("success").asBoolean());
        assertEquals(1, f.writes);
    }

    private static class Fixture implements CartService, CartOperationIdempotencyService {
        private record Key(Long userId, String operationId) { }
        private record Saved(String fingerprint, CartToolResult result) { }
        private final Map<Key, Saved> results = new HashMap<>();
        private final Map<String, Integer> quantities = new HashMap<>();
        private int writes;
        private final AiOrchestrationCore core = new AiOrchestrationCore();

        Fixture() {
            ReflectionTestUtils.setField(core, "agentRunService", new UnrecordedTestRuns());
            CartTool tool = new CartTool();
            ReflectionTestUtils.setField(tool, "cartService", this);
            ReflectionTestUtils.setField(tool, "operationIdempotencyService", this);
            ReflectionTestUtils.setField(core, "cartTool", tool);
            ReflectionTestUtils.setField(core, "conversationMemoryService", new ConversationMemoryService() {
                public List<ConversationMessage> getHistory(Long userId, String conversationId) { return List.of(); }
                public void append(Long userId, String conversationId, List<ConversationMessage> messages) { }
            });
        }

        JsonNode call(Long userId, String requestId, String operation, Long productId, Integer quantity) throws Exception {
            AiChatRequest request = new AiChatRequest();
            request.setClientRequestId(requestId);
            request.setConversationId("conversation-001");
            request.setMessage("调整购物车");
            var prepared = core.prepare(userId, request);
            ToolCallback[] callbacks = ReflectionTestUtils.invokeMethod(core, "callbacks", prepared);
            ToolCallback cart = Arrays.stream(Objects.requireNonNull(callbacks))
                    .filter(callback -> callback.getToolDefinition().name().equals("CartTool")).findFirst().orElseThrow();
            String args = JSON.writeValueAsString(Map.of("request", Map.of(
                    "operation", operation, "productId", productId, "quantity", quantity)));
            return JSON.readTree(cart.call(args, new ToolContext(Map.of("userId", userId))));
        }

        @Override
        public synchronized CartToolResult execute(Long userId, String operationId, String fingerprint,
                                                    Supplier<CartToolResult> operation) {
            Key key = new Key(userId, operationId);
            Saved existing = results.get(key);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) throw new ConflictException("同一操作标识不能用于不同购物车参数");
                return existing.result();
            }
            CartToolResult result = operation.get();
            if (result.success()) results.put(key, new Saved(fingerprint, result));
            return result;
        }

        int quantity(Long userId, Long productId) { return quantities.getOrDefault(userId + ":" + productId, 0); }

        public CartResponse addItem(Long userId, AddCartItemRequest request) {
            quantities.merge(userId + ":" + request.getProductId(), request.getQuantity(), Integer::sum);
            writes++;
            return getCart(userId);
        }
        public CartResponse updateItem(Long userId, Long productId, UpdateCartItemRequest request) {
            quantities.put(userId + ":" + productId, request.getQuantity());
            writes++;
            return getCart(userId);
        }
        public CartResponse getCart(Long userId) {
            int total = quantities.entrySet().stream().filter(entry -> entry.getKey().startsWith(userId + ":"))
                    .mapToInt(Map.Entry::getValue).sum();
            return new CartResponse(userId, List.of(), total, BigDecimal.ZERO);
        }
        public CartResponse removeItem(Long userId, Long productId) { throw new UnsupportedOperationException(); }
        public CartResponse clear(Long userId) { throw new UnsupportedOperationException(); }
    }
}
