package com.example.common;

/** 对外 API 的统一响应结构，包含状态码、消息和业务数据。 */
public record ApiResponse<T>(int code, String message, T data) {
    /** 创建成功响应。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /** 创建不携带业务数据的失败响应。 */
    public static <T> ApiResponse<T> failure(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /** 仅在错误本身需要携带安全、结构化诊断结果时使用。 */
    public static <T> ApiResponse<T> failure(int code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
