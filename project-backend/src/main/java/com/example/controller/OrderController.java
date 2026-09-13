package com.example.controller;

import com.example.common.ApiResponse;
import com.example.common.Const;
import com.example.dto.order.Request.CreateOrderRequest;
import com.example.dto.order.Request.CreateOrderPreviewRequest;
import com.example.dto.order.Request.ConfirmOrderRequest;
import com.example.dto.order.Request.OrderQueryRequest;
import com.example.dto.order.Response.OrderDraftResponse;
import com.example.dto.order.Response.OrderResponse;
import com.example.dto.order.Response.OrderSummaryResponse;
import com.example.exception.BusinessException;
import com.example.exception.UnauthorizedException;
import com.example.service.order.OrderDraftService;
import com.example.service.order.OrderService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springdoc.core.annotations.ParameterObject;

/** 登录用户模拟订单接口。 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "订单", description = "登录用户模拟订单接口")
@Validated
public class OrderController {
    @Resource
    private OrderService orderService;
    @Resource
    private OrderDraftService orderDraftService;

    @PostMapping
    @Operation(summary = "兼容旧订单入口",
            description = "旧版直接下单入口已关闭，请先创建订单预览，再通过确认接口提交订单。")
    public ApiResponse<Void> create(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "旧版参数，不再执行直接下单", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CreateOrderRequest request) {
        requireUserId(userId);
        validateIdempotencyKey(idempotencyKey);
        throw new BusinessException("请先创建订单预览，再确认订单");
    }

    @PostMapping("/preview")
    @Operation(summary = "创建订单预览",
            description = "根据当前登录用户购物车生成订单预览；只重新读取商品事实，不扣减库存、不创建订单。")
    public ApiResponse<OrderDraftResponse> preview(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @RequestBody(required = false) CreateOrderPreviewRequest request) {
        return ApiResponse.success(orderDraftService.preview(requireUserId(userId)));
    }

    @PostMapping("/{draftId}/confirm")
    @Operation(summary = "确认订单预览",
            description = "重新校验订单预览对应商品的价格、上架状态和实时库存后创建模拟订单。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "订单确认成功")
    public ResponseEntity<ApiResponse<OrderResponse>> confirm(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "订单预览业务 ID", required = true)
            @PathVariable String draftId,
            @Parameter(description = "同一用户同一确认请求的唯一幂等键，长度1至64个可打印字符", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ConfirmOrderRequest request) {
        Long currentUserId = requireUserId(userId);
        validateIdempotencyKey(idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(orderService.confirmDraft(currentUserId, draftId, idempotencyKey)));
    }

    @GetMapping
    @Operation(summary = "分页查询当前用户订单",
            description = "仅返回当前登录用户的订单，默认按创建时间和订单 ID 倒序排列。")
    public ApiResponse<Page<OrderSummaryResponse>> page(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @ParameterObject @Valid @ModelAttribute OrderQueryRequest request) {
        return ApiResponse.success(orderService.page(requireUserId(userId), request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询当前用户订单详情",
            description = "返回订单及下单时保存的商品明细快照；不存在或不属于当前用户的订单统一返回订单不存在。")
    public ApiResponse<OrderResponse> detail(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "订单业务 ID，必须为正整数", example = "1001")
            @PathVariable @Positive(message = "订单ID必须是正整数") Long id) {
        return ApiResponse.success(orderService.findDetail(requireUserId(userId), id));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "支付订单",
            description = "把当前用户的待支付订单标记为已支付；重复支付返回既有订单，"
                    + "订单已关闭或已超过支付时限时返回冲突。")
    public ApiResponse<OrderResponse> pay(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "订单业务 ID，必须为正整数", example = "1001")
            @PathVariable @Positive(message = "订单ID必须是正整数") Long id) {
        return ApiResponse.success(orderService.pay(requireUserId(userId), id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "取消订单",
            description = "关闭当前用户的待支付订单并归还已扣减库存；重复取消返回既有订单，"
                    + "订单已支付时返回冲突。")
    public ApiResponse<OrderResponse> cancel(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
            @Parameter(description = "订单业务 ID，必须为正整数", example = "1001")
            @PathVariable @Positive(message = "订单ID必须是正整数") Long id) {
        return ApiResponse.success(orderService.cancel(requireUserId(userId), id));
    }

    private Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new UnauthorizedException("未登录或登录已失效");
        }
        return userId;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64
                || idempotencyKey.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("Idempotency-Key必须是1到64个可打印字符");
        }
    }
}
