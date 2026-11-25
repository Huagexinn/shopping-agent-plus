package com.example.ai.tool.support;

import com.example.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeToolPolicyTest {

    // G02：重复调用拦截
    @Test
    void shouldRejectDuplicateToolCall() {
        NativeToolPolicy policy =
                new NativeToolPolicy(new ObjectMapper());

        // 第一次调用：允许
        assertDoesNotThrow(() ->
                policy.beforeCall(
                        "ProductDetailTool",
                        "{\"productId\":1,\"source\":\"test\"}",
                        0
                )
        );

        // 第二次调用：JSON 字段顺序不同，但内容相同
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> policy.beforeCall(
                        "ProductDetailTool",
                        "{\"source\":\"test\",\"productId\":1}",
                        1
                )
        );

        assertTrue(exception.getMessage().contains("相同参数"));

        System.out.println(
                "G02 PASS: duplicate tool call rejected"
        );
    }

    // G03：单轮最多允许 7 次调用
    @Test
    void shouldRejectEighthToolCall() {
        NativeToolPolicy policy =
                new NativeToolPolicy(new ObjectMapper());

        // 模拟前 7 次不同参数的调用
        for (int i = 0; i < 7; i++) {
            final int callIndex = i;

            assertDoesNotThrow(() ->
                    policy.beforeCall(
                            "ProductDetailTool",
                            "{\"productId\":" + (callIndex + 1) + "}",
                            callIndex
                    )
            );
        }

        // 第 8 次：executedCallCount = 7
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> policy.beforeCall(
                        "ProductDetailTool",
                        "{\"productId\":8}",
                        7
                )
        );

        assertTrue(exception.getMessage().contains("调用次数已达上限"));

        System.out.println(
                "G03 PASS: eighth tool call rejected"
        );
    }
}
