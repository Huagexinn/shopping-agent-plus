package com.example.ai.rag;

/** 向量检索依赖不可用时使用的明确失败类型。 */
public class RagUnavailableException extends RuntimeException {
    public RagUnavailableException(String message) {
        super(message);
    }

    public RagUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
