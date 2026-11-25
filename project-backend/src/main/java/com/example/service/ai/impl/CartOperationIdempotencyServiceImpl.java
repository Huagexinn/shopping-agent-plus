package com.example.service.ai.impl;

import com.example.ai.model.response.CartToolResult;
import com.example.entity.CartOperationRecordEntity;
import com.example.exception.BusinessException;
import com.example.exception.ConflictException;
import com.example.mapper.CartOperationRecordMapper;
import com.example.service.ai.CartOperationIdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.function.Supplier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redis结果缓存 + MySQL持久化幂等。
 *
 * MySQL唯一约束负责最终并发控制；
 * Redis只负责缓存已提交的操作结果。
 */
@Service
public class CartOperationIdempotencyServiceImpl
        implements CartOperationIdempotencyService {

    private static final Logger log =
            LoggerFactory.getLogger(CartOperationIdempotencyServiceImpl.class);

    private static final String KEY_PREFIX =
            "shopping:v1:cart:tool:result:";

    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCEEDED = "SUCCEEDED";

    private static final Duration RESULT_TTL = Duration.ofMinutes(10);

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private CartOperationRecordMapper recordMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    @Override
    public CartToolResult execute(
            Long userId,
            String operationId,
            String fingerprint,
            Supplier<CartToolResult> operation) {

        validateInput(userId, operationId, fingerprint, operation);

        // 1. 优先查询Redis结果缓存
        CartToolResult cached =
                readCache(userId, operationId, fingerprint);

        if (cached != null) {
            return cached;
        }

        // 2. Redis没有结果时，进入MySQL持久化幂等流程
        CartToolResult result = executeWithDatabase(
                userId,
                operationId,
                fingerprint,
                operation
        );

        // 3. 数据库事务已经提交，Redis只做尽力缓存
        writeCacheBestEffort(
                userId,
                operationId,
                fingerprint,
                result
        );

        return result;
    }

    /**
     * MySQL事务：插入操作记录、执行购物车业务、保存结果。
     */
    private CartToolResult executeWithDatabase(
            Long userId,
            String operationId,
            String fingerprint,
            Supplier<CartToolResult> operation) {

        TransactionTemplate template =
                new TransactionTemplate(transactionManager);

        // 确保操作记录和购物车修改在一个独立事务中完成
        template.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );

        try {
            CartToolResult result = template.execute(status -> {

                LocalDateTime now = LocalDateTime.now();

                // ① 创建PROCESSING操作记录
                CartOperationRecordEntity record =
                        new CartOperationRecordEntity();

                record.setUserId(userId);
                record.setOperationId(operationId);
                record.setFingerprint(fingerprint);
                record.setStatus(PROCESSING);
                record.setResultJson(null);
                record.setCreatedAt(now);
                record.setUpdatedAt(now);

                try {
                    recordMapper.insert(record);
                } catch (DuplicateKeyException exception) {
                    // 交给事务外层处理，确保当前事务回滚
                    throw new DuplicateOperationException(exception);
                }

                // ② 执行实际购物车业务
                CartToolResult operationResult = operation.get();

                if (operationResult == null) {
                    throw new IllegalStateException(
                            "购物车操作没有返回结果"
                    );
                }

                // 失败结果不持久化，回滚操作记录
                if (!operationResult.success()) {
                    throw new OperationRejectedException(operationResult);
                }

                // ③ 序列化完整Tool结果
                record.setResultJson(serialize(operationResult));
                record.setStatus(SUCCEEDED);
                record.setUpdatedAt(LocalDateTime.now());

                // ④ 更新操作记录
                if (recordMapper.updateById(record) != 1) {
                    throw new IllegalStateException(
                            "购物车幂等记录更新失败"
                    );
                }

                // ⑤ 返回结果，TransactionTemplate随后提交事务
                return operationResult;
            });

            if (result == null) {
                throw new IllegalStateException(
                        "购物车幂等事务没有返回结果"
                );
            }

            return result;

        } catch (DuplicateOperationException exception) {

            // 相同operationId已经被其他请求成功处理
            return loadExistingResult(
                    userId,
                    operationId,
                    fingerprint
            );

        } catch (OperationRejectedException exception) {

            // 业务失败，事务已回滚，返回原始失败结果
            return exception.getResult();
        }
    }

    /**
     * 读取MySQL中已经提交的操作结果。
     */
    private CartToolResult loadExistingResult(
            Long userId,
            String operationId,
            String fingerprint) {

        CartOperationRecordEntity record =
                recordMapper.selectByUserIdAndOperationId(
                        userId,
                        operationId
                );

        if (record == null) {
            throw new ConflictException(
                    "操作记录暂不可读取，请使用原操作标识重试"
            );
        }

        // 相同operationId不能用于不同业务参数
        if (!fingerprint.equals(record.getFingerprint())) {
            throw new ConflictException(
                    "同一操作标识不能用于不同购物车参数"
            );
        }

        // 正常情况下PROCESSING不会单独提交
        if (!SUCCEEDED.equals(record.getStatus())
                || record.getResultJson() == null) {

            throw new ConflictException(
                    "购物车操作尚未完成，请稍后重试"
            );
        }

        return deserialize(record.getResultJson());
    }

    /**
     * Redis缓存读取。
     * Redis不可用或缓存损坏时，降级到MySQL。
     */
    private CartToolResult readCache(
            Long userId,
            String operationId,
            String fingerprint) {

        if (redisTemplate == null) {
            return null;
        }

        String key = cacheKey(userId, operationId);

        try {
            String value =
                    redisTemplate.opsForValue().get(key);

            if (value == null) {
                return null;
            }

            CachedOperation cached =
                    MAPPER.readValue(value, CachedOperation.class);

            if (cached == null
                    || cached.fingerprint() == null
                    || cached.result() == null) {

                deleteCacheQuietly(key);
                return null;
            }

            // 缓存指纹不一致时，不信任缓存，交给MySQL最终判断
            if (!fingerprint.equals(cached.fingerprint())) {
                return null;
            }

            return cached.result();

        } catch (Exception exception) {
            log.warn("Redis购物车结果读取失败，降级使用MySQL");
            deleteCacheQuietly(key);
            return null;
        }
    }

    /**
     * MySQL提交成功之后，尽力写入Redis。
     */
    private void writeCacheBestEffort(
            Long userId,
            String operationId,
            String fingerprint,
            CartToolResult result) {

        if (redisTemplate == null
                || result == null
                || !result.success()) {
            return;
        }

        try {
            String value = MAPPER.writeValueAsString(
                    new CachedOperation(fingerprint, result)
            );

            redisTemplate.opsForValue().set(
                    cacheKey(userId, operationId),
                    value,
                    RESULT_TTL
            );

        } catch (Exception exception) {
            // 数据库已经提交，不能因为缓存失败而返回业务失败
            log.warn("Redis购物车结果缓存写入失败，后续请求将查询MySQL");
        }
    }

    private String serialize(CartToolResult result) {
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "购物车操作结果序列化失败",
                    exception
            );
        }
    }

    private CartToolResult deserialize(String json) {
        try {
            return MAPPER.readValue(json, CartToolResult.class);
        } catch (Exception exception) {
            log.error("MySQL购物车操作结果反序列化失败", exception);
            throw new IllegalStateException(
                    "历史购物车操作结果无法读取",
                    exception
            );
        }
    }

    private void deleteCacheQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Redis损坏缓存清理失败");
        }
    }

    private String cacheKey(Long userId, String operationId) {
        return KEY_PREFIX + userId + ":" + digest(operationId);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256算法不可用", exception);
        }
    }

    private void validateInput(
            Long userId,
            String operationId,
            String fingerprint,
            Supplier<CartToolResult> operation) {

        if (userId == null
                || userId <= 0
                || operationId == null
                || operationId.isBlank()
                || operationId.length() > 128
                || operationId.chars().anyMatch(Character::isISOControl)
                || fingerprint == null
                || !fingerprint.matches("[a-fA-F0-9]{64}")
                || operation == null) {

            throw new BusinessException(
                    "购物车操作参数不正确"
            );
        }
    }

    private record CachedOperation(
            String fingerprint,
            CartToolResult result) {
    }

    /**
     * 专门标识数据库唯一键冲突。
     */
    private static class DuplicateOperationException
            extends RuntimeException {

        private DuplicateOperationException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 业务失败时触发事务回滚，同时保留原始失败结果。
     */
    private static class OperationRejectedException
            extends RuntimeException {

        private final CartToolResult result;

        private OperationRejectedException(CartToolResult result) {
            this.result = result;
        }

        private CartToolResult getResult() {
            return result;
        }
    }
}
