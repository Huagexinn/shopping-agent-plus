package com.example.controller;

import com.example.common.ApiResponse;
import com.example.common.Const;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.dto.ai.Response.AiStreamEventResponse;
import com.example.exception.UnauthorizedException;
import com.example.service.ai.AiChatService;
import com.example.service.ai.impl.AiChatStreamDriver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** AI 商品助手接口；购物意图只生成订单预览，不执行订单确认。 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI助手", description = "手机商品问答、购物车/订单查询和安全订单预览接口")
@Validated
public class AiController {
    @Resource
    private AiChatService aiChatService;
    @Resource
    private AiChatStreamDriver aiChatStreamDriver;

    @PostMapping("/chat")
    @Operation(summary = "AI 聊天与购物编排",
            description = "基于已核实商品事实返回同步 JSON 回复；" +
                    "购物意图最多生成订单预览，确认订单必须由客户端显式调用确认接口。")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "AI 业务结果（通过 code 和 data.resultStatus 区分成功或失败）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "请求参数不合法")
    })
    public ApiResponse<AiChatResponse> chat(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Valid @RequestBody AiChatRequest request) {
        AiChatResponse response = aiChatService.chat(requireUserId(userId), request);
        if (response.successful()) {
            return ApiResponse.success(response);
        }
        return ApiResponse.failure(response.resultStatus().code(), response.message(), response);
    }

    /**
     * 流式聊天。与 {@link #chat} 共用同一套模型编排、Tool 执行、身份边界和事实校验。
     *
     * <p>参数校验和未登录在流开始前就失败，所以仍返回正常的 400 / 401；一旦开始推送，响应头已提交，
     * 后续错误只能通过 {@code error} 事件传达，HTTP 状态码固定为 200。</p>
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 聊天流式响应（SSE）",
            description = "以 text/event-stream 依次推送 meta、text_delta 事件；" +
                    "正常结束推送携带完整响应的 final 事件，异常结束推送 error 事件。" +
                    "与同步接口共用同一套编排与事实校验，不推送未经校验的模型原始输出。")
    public Flux<ServerSentEvent<Object>> chatStream(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Valid @RequestBody AiChatRequest request) {
        return aiChatStreamDriver.stream(requireUserId(userId), request)
                .map(this::toServerSentEvent);
    }

    private ServerSentEvent<Object> toServerSentEvent(AiStreamEventResponse event) {
        return ServerSentEvent.<Object>builder(event.payload())
                .event(event.event())
                .build();
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("未登录或登录已失效");
        }
        return userId;
    }
}
