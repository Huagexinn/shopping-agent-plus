package com.example.dto.auth.Response;

/** 登录成功后返回的 JWT 和用户摘要。 */
public record LoginResponse(String token, String tokenType, long expiresIn, UserProfileResponse user) {
}
