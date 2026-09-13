package com.example.exception;

/** 登录凭据无效。 */
public class UnauthorizedException extends BusinessException {
    public UnauthorizedException(String message) {
        super(4011, message);
    }
}
