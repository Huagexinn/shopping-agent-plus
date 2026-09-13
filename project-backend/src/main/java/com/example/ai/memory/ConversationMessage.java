package com.example.ai.memory;

/** 会话记忆中的最小消息单元。 */
public record ConversationMessage(String role, String content) {
}
