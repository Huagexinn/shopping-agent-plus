package com.example.dto.ai.Response;

/** SSE 事件信封；{@code event} 是事件名，{@code payload} 是该事件的载荷。 */
public record AiStreamEventResponse(String event, Object payload) {

    public static AiStreamEventResponse of(String event, Object payload) {
        return new AiStreamEventResponse(event, payload);
    }
}
