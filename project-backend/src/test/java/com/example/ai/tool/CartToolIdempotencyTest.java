package com.example.ai.tool;

import com.example.ai.model.request.CartToolRequest;
import com.example.ai.model.response.CartToolResult;
import com.example.exception.ConflictException;
import com.example.service.ai.CartOperationIdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
@SpringBootTest
class CartToolIdempotencyTest {

    @Autowired
    private CartTool cartTool;

    @Test
    void testDifferentOperationId() {

        Long userId = 1L;
        Long productId = 1L;

        // 使用一个全新的操作标识
        String operationId = "cart-test-003";

        CartToolRequest request = new CartToolRequest(
                "add",
                productId,
                2,
                operationId
        );

        CartToolResult result = cartTool.execute(userId, request);

        System.out.println("第三次业务操作结果：" + result);

        assertTrue(result.success(), result.message());
    }

    @Test
    void testConcurrentDuplicateAdd() throws Exception {

        Long userId = 1L;
        Long productId = 1L;
        String operationId = "cart-test-concurrent-003";

        int threadCount = 50;

        CartToolRequest request = new CartToolRequest(
                "add",
                productId,
                2,
                operationId
        );

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<CartToolResult>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {

                Future<CartToolResult> future = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cartTool.execute(userId, request);
                });

                futures.add(future);
            }

            // 等待所有线程准备完成
            ready.await();

            // 同时释放所有线程
            start.countDown();

            int successCount = 0;
            int conflictCount = 0;

            for (Future<CartToolResult> future : futures) {
                try {
                    CartToolResult result = future.get();

                    if (result.success()) {
                        successCount++;
                    }

                    System.out.println(result);

                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();

                    if (cause instanceof ConflictException) {
                        conflictCount++;
                    } else {
                        throw e;
                    }
                }
            }

            System.out.println("成功请求数：" + successCount);
            System.out.println("冲突请求数：" + conflictCount);

        } finally {
            executor.shutdownNow();
        }
    }

    @Autowired
    private CartOperationIdempotencyService idempotencyService;

    @Test
    void testMysqlConcurrentIdempotency() throws Exception {

        Long userId = 1L;

        // 每次测试使用新的操作标识
        String operationId = "cart-test-" + UUID.randomUUID();

        // 必须是64位十六进制字符串
        String fingerprint = "a".repeat(64);

        AtomicInteger executeCount = new AtomicInteger(0);

        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 第一个请求
            Future<CartToolResult> future1 = executor.submit(() ->
                    idempotencyService.execute(
                            userId,
                            operationId,
                            fingerprint,
                            () -> {
                                executeCount.incrementAndGet();

                                firstEntered.countDown();

                                try {
                                    allowFinish.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(e);
                                }

                                return new CartToolResult(
                                        "CartTool",
                                        true,
                                        "add",
                                        "执行成功",
                                        null,
                                        1L
                                );
                            }
                    )
            );

            // 确保第一个请求已经进入业务
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

            // 第二个请求：使用相同的操作标识
            Future<CartToolResult> future2 = executor.submit(() -> {
                secondStarted.countDown();

                return idempotencyService.execute(
                        userId,
                        operationId,
                        fingerprint,
                        () -> {
                            executeCount.incrementAndGet();

                            return new CartToolResult(
                                    "CartTool",
                                    true,
                                    "add",
                                    "执行成功",
                                    null,
                                    1L
                            );
                        }
                );
            });

            // 确保第二个请求开始执行
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));

            // 释放第一个请求，允许事务提交
            allowFinish.countDown();

            CartToolResult result1 = future1.get(10, TimeUnit.SECONDS);
            CartToolResult result2 = future2.get(10, TimeUnit.SECONDS);

            System.out.println("第一个请求：" + result1);
            System.out.println("第二个请求：" + result2);
            System.out.println("实际业务执行次数：" + executeCount.get());

            assertTrue(result1.success());
            assertTrue(result2.success());
            assertEquals(1, executeCount.get());

        } finally {
            allowFinish.countDown();
            executor.shutdownNow();
        }
    }
}