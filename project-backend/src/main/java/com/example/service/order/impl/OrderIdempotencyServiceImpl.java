package com.example.service.order.impl;

import com.example.service.order.OrderIdempotencyService;
import com.example.exception.ConflictException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 基于 Redis 的订单幂等结果索引和 processing 锁实现。 */
@Service
public class OrderIdempotencyServiceImpl implements OrderIdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(OrderIdempotencyServiceImpl.class);
    private static final String KEY_PREFIX = "shopping:v1:order:";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration RESULT_TTL = Duration.ofHours(24);
    private static final Duration DRAFT_BINDING_TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    public Long getResult(Long userId, String idempotencyKey) {
        if (!validInput(userId, idempotencyKey) || redisTemplate == null) {
            return null;
        }
        String key = resultKey(userId, idempotencyKey);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException exception) {
                redisTemplate.delete(key);
                log.warn("订单幂等结果缓存格式无效，已删除损坏缓存");
                return null;
            }
        } catch (RuntimeException exception) {
            log.warn("Redis订单幂等结果读取失败，降级使用数据库");
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        if (redisTemplate == null || redisTemplate.getConnectionFactory() == null) {
            return false;
        }
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            return connection.ping() != null;
        } catch (RuntimeException exception) {
            log.warn("Redis订单幂等健康检查失败");
            return false;
        }
    }

    @Override
    public String getDraftId(Long userId, String idempotencyKey) {
        if (!validInput(userId, idempotencyKey) || redisTemplate == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(draftBindingKey(userId, idempotencyKey));
        } catch (RuntimeException exception) {
            log.warn("Redis订单草稿幂等绑定读取失败");
            throw new IllegalStateException("Redis订单幂等服务不可用", exception);
        }
    }

    @Override
    public void bindDraft(Long userId, String idempotencyKey, String draftId) {
        if (!validInput(userId, idempotencyKey) || draftId == null || draftId.isBlank()
                || redisTemplate == null) {
            throw new IllegalStateException("Redis订单幂等服务不可用");
        }
        try {
            String key = draftBindingKey(userId, idempotencyKey);
            String existing = redisTemplate.opsForValue().get(key);
            if (existing != null && !existing.equals(draftId)) {
                throw new IllegalArgumentException("同一幂等键不能确认不同订单预览");
            }
            boolean bound = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, draftId, DRAFT_BINDING_TTL));
            if (!bound) {
                String concurrent = redisTemplate.opsForValue().get(key);
                if (concurrent != null && !concurrent.equals(draftId)) {
                    throw new ConflictException("同一幂等键不能确认不同订单预览");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (ConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Redis订单草稿幂等绑定写入失败");
            throw new IllegalStateException("Redis订单幂等服务不可用", exception);
        }
    }

    @Override
    public LockResult tryAcquire(Long userId, String idempotencyKey) {
        if (!validInput(userId, idempotencyKey) || redisTemplate == null) {
            return new LockResult(false, false, null);
        }
        String ownerToken = UUID.randomUUID().toString();
        try {
            boolean acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    processingKey(userId, idempotencyKey), ownerToken, PROCESSING_TTL));
            return new LockResult(true, acquired, acquired ? ownerToken : null);
        } catch (RuntimeException exception) {
            log.warn("Redis订单处理锁获取失败，降级使用数据库");
            return new LockResult(false, false, null);
        }
    }

    @Override
    public void saveResult(Long userId, String idempotencyKey, Long orderId) {
        if (!validInput(userId, idempotencyKey) || orderId == null || orderId <= 0 || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(resultKey(userId, idempotencyKey), orderId.toString(), RESULT_TTL);
        } catch (RuntimeException exception) {
            log.warn("Redis订单幂等结果写入失败，后续请求将回查数据库");
        }
    }

    @Override
    public void release(Long userId, String idempotencyKey, String ownerToken) {
        if (!validInput(userId, idempotencyKey) || ownerToken == null || ownerToken.isBlank()
                || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT,
                    List.of(processingKey(userId, idempotencyKey)), ownerToken);
        } catch (RuntimeException exception) {
            log.warn("Redis订单处理锁释放失败，等待锁自动过期");
        }
    }

    private String processingKey(Long userId, String idempotencyKey) {
        return KEY_PREFIX + "processing:" + userId + ":" + digest(idempotencyKey);
    }

    private String resultKey(Long userId, String idempotencyKey) {
        return KEY_PREFIX + "result:" + userId + ":" + digest(idempotencyKey);
    }

    private String draftBindingKey(Long userId, String idempotencyKey) {
        return KEY_PREFIX + "draft:" + userId + ":" + digest(idempotencyKey);
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256算法不可用", exception);
        }
    }

    private boolean validInput(Long userId, String idempotencyKey) {
        return userId != null && userId > 0 && idempotencyKey != null
                && !idempotencyKey.isBlank() && idempotencyKey.length() <= 64
                && idempotencyKey.chars().noneMatch(Character::isISOControl);
    }
}
