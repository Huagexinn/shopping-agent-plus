package com.example.dto.ai.Response;

/**
 * 流式异常结束事件。
 *
 * <p>{@code code} 与同步接口的 {@code ApiResponse.code} 取自同一份映射
 * （{@link AiChatResultStatus#code()}），前端错误处理不需要为流式另写一套。</p>
 */
public record AiStreamErrorResponse(int code, String message, AiChatResultStatus resultStatus) {
}
