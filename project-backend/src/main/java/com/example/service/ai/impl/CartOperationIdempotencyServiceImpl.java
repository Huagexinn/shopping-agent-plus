package com.example.service.ai.impl;

import com.example.ai.model.response.CartToolResult;
import com.example.exception.BusinessException;
import com.example.exception.ConflictException;
import com.example.service.ai.CartOperationIdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 基于 Redis 的购物车 Tool 写操作短期去重实现。 */
@Service
public class CartOperationIdempotencyServiceImpl implements CartOperationIdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(CartOperationIdempotencyServiceImpl.class);
    private static final String KEY_PREFIX = "shopping:v1:cart:tool:";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration RESULT_TTL = Duration.ofMinutes(10);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> RELEASE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    public CartToolResult execute(Long userId, String operationId, String fingerprint,
                                  Supplier<CartToolResult> operation) {
        validateInput(userId, operationId, fingerprint, operation);
        if (redisTemplate == null) {
            throw unavailable();
        }

        String resultKey = resultKey(userId, operationId);
        String processingKey = processingKey(userId, operationId);
        String ownerToken = UUID.randomUUID().toString();
        try {
            CartToolResult cached = readResult(resultKey, fingerprint);
            if (cached != null) {
                return cached;
            }
            boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(processingKey, ownerToken, PROCESSING_TTL));
            if (!acquired) {
                cached = readResult(resultKey, fingerprint);
                if (cached != null) {
                    return cached;
                }
                throw new ConflictException("购物车操作正在处理中，请稍后重试");
            }
            CartToolResult result = operation.get();
            writeResult(resultKey, fingerprint, result);
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Redis购物车 Tool 去重失败，拒绝本次写操作", exception);
            throw unavailable();
        } finally {
            release(processingKey, ownerToken);
        }
    }

    private CartToolResult readResult(String key, String fingerprint) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        try {
            CachedOperation cached = MAPPER.readValue(value, CachedOperation.class);
            if (cached == null || cached.fingerprint() == null || cached.result() == null) {
                redisTemplate.delete(key);
                throw unavailable();
            }
            if (!cached.fingerprint().equals(fingerprint)) {
                throw new ConflictException("同一操作标识不能用于不同购物车参数");
            }
            return cached.result();
        } catch (ConflictException exception) {
            throw exception;
        } catch (Exception exception) {
            redisTemplate.delete(key);
            log.warn("Redis购物车 Tool 结果缓存格式无效，已删除损坏缓存");
            throw unavailable();
        }
    }

    private void writeResult(String key, String fingerprint, CartToolResult result) {
        try {
            redisTemplate.opsForValue().set(key,
                    MAPPER.writeValueAsString(new CachedOperation(fingerprint, result)), RESULT_TTL);
        } catch (Exception exception) {
            log.warn("Redis购物车 Tool 结果写入失败，本次写操作拒绝返回成功", exception);
            throw unavailable();
        }
    }

    private void release(String key, String ownerToken) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), ownerToken);
        } catch (RuntimeException exception) {
            log.warn("Redis购物车 Tool 处理锁释放失败，等待锁自动过期");
        }
    }

    private String processingKey(Long userId, String operationId) {
        return KEY_PREFIX + "processing:" + userId + ":" + digest(operationId);
    }

    private String resultKey(Long userId, String operationId) {
        return KEY_PREFIX + "result:" + userId + ":" + digest(operationId);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256算法不可用", exception);
        }
    }

    private void validateInput(Long userId, String operationId, String fingerprint,
                               Supplier<CartToolResult> operation) {
        if (userId == null || userId <= 0 || operationId == null || operationId.isBlank()
                || operationId.length() > 128 || operationId.chars().anyMatch(Character::isISOControl)
                || fingerprint == null || fingerprint.isBlank() || operation == null) {
            throw new BusinessException("购物车操作参数不正确");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException("购物车写操作暂不可用，请稍后重试");
    }

    private record CachedOperation(String fingerprint, CartToolResult result) {
    }
}
