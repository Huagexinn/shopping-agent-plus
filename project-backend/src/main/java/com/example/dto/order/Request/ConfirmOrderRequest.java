package com.example.dto.order.Request;

/** 确认订单请求；订单草稿从路径读取，幂等键从请求头读取。 */
public record ConfirmOrderRequest(
        @jakarta.validation.constraints.NotNull
        @jakarta.validation.constraints.AssertTrue(message = "必须明确确认订单预览") Boolean confirmed,
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{64}", message = "预览指纹格式不正确") String fingerprint) {}
