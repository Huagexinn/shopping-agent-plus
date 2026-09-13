package com.example.dto.ai.Response;

/** AI 聊天本轮结果状态；与订单预览等购物编排状态分开表达。 */
public enum AiChatResultStatus {
    SUCCESS(0),
    MODEL_TIMEOUT(5041),
    MODEL_ERROR(5021),
    EMPTY_RESPONSE(5022),
    TOOL_ERROR(5023),
    RAG_UNAVAILABLE(5031),
    GROUNDING_REJECTED(4221);

    private final int code;

    AiChatResultStatus(int code) {
        this.code = code;
    }

    /**
     * 对外错误码。同步接口和流式接口共用这一份映射，避免两处各写一遍导致码值漂移。
     */
    public int code() {
        return code;
    }
}
