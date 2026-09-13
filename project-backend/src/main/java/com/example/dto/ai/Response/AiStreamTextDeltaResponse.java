package com.example.dto.ai.Response;

/** 正文增量事件：{@code delta} 是本次新增的文本片段，前端按顺序追加。 */
public record AiStreamTextDeltaResponse(String delta) {
}
