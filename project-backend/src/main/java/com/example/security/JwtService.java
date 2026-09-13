package com.example.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/** 负责 JWT 的生成和签名校验。 */
@Service
public class JwtService {
    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtService(@Value("${spring.security.jwt.key}") String secret,
                      @Value("${spring.security.jwt.expire:72}") long expirationHours) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT 签名密钥不能为空");
        }
        if (expirationHours <= 0) {
            throw new IllegalArgumentException("JWT 有效期必须大于零");
        }
        this.signingKey = Keys.hmacShaKeyFor(sha256(secret));
        this.expirationMillis = TimeUnit.HOURS.toMillis(expirationHours);
    }

    /** 将任意长度的配置字符串转换为固定长度的 HMAC-SHA 密钥。 */
    private byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 算法不可用", exception);
        }
    }

    /** 根据用户标识和用户名生成带过期时间的 JWT。 */
    public String generateToken(Long userId, String username) {
        if (userId == null || userId <= 0 || username == null || username.isBlank()) {
            throw new IllegalArgumentException("JWT 用户主题和用户 ID 不能为空");
        }
        Date now = new Date();
        return Jwts.builder().subject(username).claim("userId", userId)
                .issuedAt(now).expiration(new Date(now.getTime() + expirationMillis))
                .signWith(signingKey).compact();
    }

    /** 返回与 JWT exp 一致的有效期秒数。 */
    public long getExpirationSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(expirationMillis);
    }

    /** 校验并解析 JWT，令牌无效时由 JJWT 抛出异常。 */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("JWT 令牌不能为空");
        }
        Claims claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        if (claims.getSubject() == null || claims.getSubject().isBlank()
                || claims.get("userId") == null
                || claims.getIssuedAt() == null
                || claims.getExpiration() == null) {
            throw new IllegalArgumentException("JWT 缺少必需的声明");
        }
        Object userId = claims.get("userId");
        if (!(userId instanceof Number) || ((Number) userId).longValue() <= 0) {
            throw new IllegalArgumentException("JWT 用户 ID 声明无效");
        }
        return claims;
    }
}
