package com.example.dto.ai.Request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** AI 聊天请求；购物写操作仍受服务端确认边界约束。 */
@Data
public class AiChatRequest {
    @NotBlank(message = "会话ID不能为空")
    @Size(min = 3, max = 64, message = "会话ID长度必须在3到64个字符之间")
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{2,63}", message = "会话ID只能包含字母、数字、下划线和短横线")
    private String conversationId;

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "消息内容不能超过2000个字符")
    private String message;
}
