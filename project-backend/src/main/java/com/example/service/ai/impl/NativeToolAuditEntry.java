package com.example.service.ai.impl;

import java.time.LocalDateTime;

/** 单次模型 Tool 调用的审计记录。 */
public record NativeToolAuditEntry(
        int sequence,
        String toolName,
        String arguments,
        LocalDateTime requestedAt,
        long startedAtNanos,
        Object result,
        String failureMessage,
        String outcome,
        Long durationMs) {
}
