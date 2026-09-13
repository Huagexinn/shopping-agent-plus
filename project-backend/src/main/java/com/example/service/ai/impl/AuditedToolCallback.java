package com.example.service.ai.impl;

import com.example.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

/** 在不改变 Tool 协议的前提下附加审计、去重和失败结构化回传。 */
public final class AuditedToolCallback implements ToolCallback {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Logger log = LoggerFactory.getLogger(AuditedToolCallback.class);

    private final ToolCallback delegate;
    private final NativeToolAudit audit;

    public AuditedToolCallback(ToolCallback delegate, NativeToolAudit audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String arguments) {
        return call(arguments, null);
    }

    @Override
    public String call(String arguments, ToolContext context) {
        NativeToolAuditEntry entry;
        try {
            entry = audit.start(getToolDefinition().name(), arguments);
        } catch (BusinessException exception) {
            audit.policyRejected(getToolDefinition().name(), exception.getMessage());
            return serializeToolFailure(getToolDefinition().name(), exception.getMessage(), false, "POLICY_REJECTED");
        }
        try {
            String result = delegate.call(arguments, context);
            audit.complete(entry, NativeToolAudit.parseToolResult(getToolDefinition().name(), result));
            return result;
        } catch (BusinessException exception) {
            audit.fail(entry, exception);
            boolean retryable = exception.getCode() == 4000;
            return serializeToolFailure(getToolDefinition().name(), exception.getMessage(), retryable,
                    retryable ? "PARAMETER_ERROR" : "BUSINESS_ERROR");
        } catch (RuntimeException exception) {
            audit.fail(entry, exception);
            log.warn("AI模型Tool执行失败 seq={} tool={}", entry.sequence(), getToolDefinition().name());
            throw exception;
        }
    }

    private String serializeToolFailure(String toolName, String message, boolean retryable, String failureType) {
        Map<String, Object> payload = Map.of(
                "toolName", toolName,
                "success", false,
                "retryable", retryable,
                "failureType", failureType,
                "message", message == null || message.isBlank() ? "Tool执行失败" : message,
                "productIds", List.of(),
                "total", 0);
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception serializationException) {
            throw new IllegalStateException("Tool失败结果序列化失败", serializationException);
        }
    }
}
