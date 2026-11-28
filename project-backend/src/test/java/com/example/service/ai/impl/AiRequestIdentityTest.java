package com.example.service.ai.impl;

import com.example.ai.memory.ConversationMemoryService;
import com.example.ai.memory.ConversationMessage;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.exception.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 请求契约回归：HTTP 参数校验与编排入口一致，重试不会丢失客户端标识。 */
class AiRequestIdentityTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidation() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidation() {
        validatorFactory.close();
    }

    static Stream<String> invalidIds() {
        return Stream.of(null, "", "   ", "1234567", "a".repeat(65),
                "bad/id-123", "bad id-123", " leading-123", "trailing-123 ", "invalid-123\n");
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void rejectsInvalidIdentityAtBothEntrypoints(String id) {
        AiChatRequest request = request(id);
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("clientRequestId")));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new AiOrchestrationCore().prepare(1L, request));
        assertTrue(exception.getMessage().contains("请求标识"));
    }

    static Stream<String> validIds() {
        return Stream.of("12345678", "a".repeat(64), "4cf3377e-4c43-4a49-a2b6-6431fcf6d2ac");
    }

    @ParameterizedTest
    @MethodSource("validIds")
    void retainsIdentityAcrossRepeatedPreparation(String id) {
        AiOrchestrationCore core = coreWithEmptyMemory();
        AiChatRequest request = request(id);
        assertTrue(validator.validate(request).isEmpty());
        var first = core.prepare(1L, request);
        var retry = core.prepare(1L, request);
        assertNotSame(request, first.request());
        assertEquals(id, first.request().getClientRequestId());
        assertEquals(id, retry.request().getClientRequestId());
        assertEquals("conversation-001", retry.request().getConversationId());
        assertEquals("加购一台", retry.request().getMessage());
    }

    @Test
    void preservesDistinctSubmissionsEvenWhenMessagesAreIdentical() {
        AiOrchestrationCore core = coreWithEmptyMemory();
        var first = core.prepare(1L, request("request-001"));
        var second = core.prepare(1L, request("request-002"));
        assertEquals(first.request().getMessage(), second.request().getMessage());
        assertNotEquals(first.request().getClientRequestId(), second.request().getClientRequestId());
    }

    private AiOrchestrationCore coreWithEmptyMemory() {
        ConversationMemoryService memory = new ConversationMemoryService() {
            @Override
            public List<ConversationMessage> getHistory(Long userId, String conversationId) {
                return List.of();
            }

            @Override
            public void append(Long userId, String conversationId, List<ConversationMessage> messages) {
                // 此测试只验证请求准备阶段，无需连接 Redis 或写入历史。
            }
        };
        AiOrchestrationCore core = new AiOrchestrationCore();
        ReflectionTestUtils.setField(core, "agentRunService", new UnrecordedTestRuns());
        ReflectionTestUtils.setField(core, "conversationMemoryService", memory);
        return core;
    }

    private AiChatRequest request(String id) {
        AiChatRequest request = new AiChatRequest();
        request.setClientRequestId(id);
        request.setConversationId("conversation-001");
        request.setMessage("加购一台");
        return request;
    }
}
