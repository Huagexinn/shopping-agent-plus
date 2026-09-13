package com.example.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 基于 Redis 的按用户隔离会话记忆；Redis 不可用时按无历史上下文降级。 */
@Service
@Primary
public class RedisConversationMemoryService implements ConversationMemoryService {
    private static final Logger log = LoggerFactory.getLogger(RedisConversationMemoryService.class);
    private static final String KEY_PREFIX = "shopping:conversation:";
    private static final int MAX_MESSAGES = 12;
    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Resource
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Override
    public List<ConversationMessage> getHistory(Long userId, String conversationId) {
        validateScope(userId, conversationId);
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) {
            return List.of();
        }
        String key = key(userId, conversationId);
        try {
            List<String> values = redis.opsForList().range(key, 0, -1);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<ConversationMessage> history = new ArrayList<>(values.size());
            for (String value : values) {
                history.add(MAPPER.readValue(value, ConversationMessage.class));
            }
            return List.copyOf(history);
        } catch (Exception exception) {
            log.warn("Redis会话记忆读取失败，按无历史上下文继续");
            deleteQuietly(redis, key);
            return List.of();
        }
    }

    @Override
    public void append(Long userId, String conversationId, List<ConversationMessage> messages) {
        validateScope(userId, conversationId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        StringRedisTemplate redis = redisTemplate();
        if (redis == null) {
            return;
        }
        String key = key(userId, conversationId);
        try {
            List<String> serialized = messages.stream()
                    .map(this::normalizeMessage)
                    .map(message -> serialize(message))
                    .toList();
            redis.opsForList().rightPushAll(key, serialized);
            redis.opsForList().trim(key, -MAX_MESSAGES, -1);
            redis.expire(key, TTL);
        } catch (RuntimeException exception) {
            log.warn("Redis会话记忆写入失败，后续请求按无历史上下文处理");
        }
    }

    private ConversationMessage normalizeMessage(ConversationMessage message) {
        if (message == null || message.role() == null || message.role().isBlank()
                || message.content() == null || message.content().isBlank()) {
            throw new BusinessException("会话记忆消息不能为空");
        }
        String role = message.role().trim();
        String content = message.content().trim();
        if (content.length() > MAX_MESSAGE_LENGTH) {
            content = content.substring(0, MAX_MESSAGE_LENGTH);
        }
        return new ConversationMessage(role, content);
    }

    private String serialize(ConversationMessage message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (Exception exception) {
            throw new IllegalStateException("会话记忆序列化失败", exception);
        }
    }

    private void validateScope(Long userId, String conversationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("会话用户不能为空");
        }
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
            throw new BusinessException("会话ID格式不正确");
        }
    }

    private String key(Long userId, String conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }

    private StringRedisTemplate redisTemplate() {
        return redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
    }

    private void deleteQuietly(StringRedisTemplate redis, String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ignored) {
            log.warn("Redis会话记忆损坏缓存清理失败");
        }
    }
}
