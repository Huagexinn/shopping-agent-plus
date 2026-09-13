package com.example.exception;

import lombok.Getter;

/** 面向业务规则失败场景的运行时异常。 */
@Getter
public class BusinessException extends RuntimeException {
    /**
     * -- GETTER --
     * 获取用于统一响应的业务错误码。
     */
    private final int code;

    /** 使用默认业务错误码创建异常。 */
    public BusinessException(String message) {
        this(4000, message);
    }

    /** 使用指定业务错误码和消息创建异常。 */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

}
