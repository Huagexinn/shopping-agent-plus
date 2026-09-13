package com.example.exception;

/** 请求的业务资源不存在或不可公开访问。 */
public class ResourceNotFoundException extends BusinessException {
    /** 创建资源不存在异常。 */
    public ResourceNotFoundException(String message) {
        super(4040, message);
    }
}
