package com.example.dto.ai.Response;

/**
 * 校验中断事件：前端必须丢弃已渲染的全部内容，整体替换为 {@code message}。
 *
 * <p>{@code message} 是**完整替换文本**，不是增量、也不是「从第 N 个字符起替换」。校验失败时它就是
 * 服务端固定的拒绝提示语（非流式的 {@code GROUNDING_REJECTED} 响应里 message 的同一段文本），
 * 因此替换后的最终呈现与同步接口逐字一致。</p>
 */
public record AiStreamReplaceResponse(String message, String failureType) {
}
