package com.example.ai.memory;

import java.util.List;

/** 按用户和会话读取、追加最小聊天上下文的抽象。 */
public interface ConversationMemoryService {
    List<ConversationMessage> getHistory(Long userId, String conversationId);

    void append(Long userId, String conversationId, List<ConversationMessage> messages);
}
