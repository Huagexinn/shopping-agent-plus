package com.example.exception;

/** 当前请求与同一资源的处理中操作发生冲突。 */
public class ConflictException extends BusinessException {
    /** 创建冲突异常。 */
    public ConflictException(String message) {
        super(4090, message);
    }
}
