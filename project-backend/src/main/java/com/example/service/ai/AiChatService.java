package com.example.service.ai;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;

/** AI 聊天与安全购物编排服务。 */
public interface AiChatService {
    AiChatResponse chat(Long userId, AiChatRequest request);
}
