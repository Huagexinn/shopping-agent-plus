package com.example.service.ai.impl;

import com.example.ai.model.request.CartToolRequest;
import com.example.ai.model.request.InventoryToolRequest;
import com.example.ai.model.request.OrderQueryToolRequest;
import com.example.ai.model.response.CartToolResult;
import com.example.ai.model.response.OrderQueryToolResult;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.tool.CartTool;
import com.example.ai.tool.InventoryTool;
import com.example.ai.tool.OrderQueryTool;
import com.example.ai.tool.ProductDetailTool;
import com.example.ai.rag.HybridRagService;
import com.example.dto.order.Response.OrderDraftResponse;
import com.example.exception.BusinessException;
import com.example.exception.UnauthorizedException;
import com.example.service.order.OrderDraftService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/** 为单次请求创建的原生 Tool 集合；用户身份只从 ToolContext 读取。 */
public final class NativeToolSet {
    private final Long userId;
    private final String clientRequestId;
    private final NativeToolAudit audit;
    private final ProductDetailTool productDetailTool;
    private final InventoryTool inventoryTool;
    private final CartTool cartTool;
    private final OrderQueryTool orderQueryTool;
    private final OrderDraftService orderDraftService;
    private final HybridRagService hybridRagService;

    public NativeToolSet(Long userId, String clientRequestId, NativeToolAudit audit,
                         ProductDetailTool productDetailTool, InventoryTool inventoryTool,
                         CartTool cartTool, OrderQueryTool orderQueryTool, OrderDraftService orderDraftService,
                         HybridRagService hybridRagService) {
        this.userId = userId;
        if (clientRequestId == null || !clientRequestId.matches("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")) {
            throw new BusinessException("请求标识格式不正确");
        }
        this.clientRequestId = clientRequestId;
        this.audit = audit;
        this.productDetailTool = productDetailTool;
        this.inventoryTool = inventoryTool;
        this.cartTool = cartTool;
        this.orderQueryTool = orderQueryTool;
        this.orderDraftService = orderDraftService;
        this.hybridRagService = hybridRagService;
    }

    @Tool(name = "HybridRagTool", description = "检索商城商品和知识资料。商品推荐、商品搜索、规格、兼容性、配送和售后问题需要检索时调用；返回商品ID及经过MySQL实时回查的商品事实")
    public HybridRagService.RetrievalResult hybridRag(@ToolParam(description = "用户需要检索的完整问题") String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索问题不能为空");
        }
        return hybridRagService.retrieve(query.trim());
    }

    @Tool(name = "ProductDetailTool", description = "查询一个在售商品的最新详情、价格、规格和库存")
    public ReadOnlyToolResult detail(@ToolParam(description = "商品ID") Long productId) {
        return productDetailTool.detail(productId);
    }

    @Tool(name = "InventoryTool", description = "查询商品当前可用库存")
    public ReadOnlyToolResult inventory(@ToolParam(description = "库存查询条件") InventoryToolRequest request) {
        if (request != null && hasPositiveProductIds(request.productIds())) {
            audit.requireKnownProductIds("InventoryTool", request.productIds());
        }
        return inventoryTool.inventory(request);
    }

    private boolean hasPositiveProductIds(List<Long> productIds) {
        return productIds != null && !productIds.isEmpty()
                && productIds.stream().allMatch(productId -> productId != null && productId > 0);
    }

    @Tool(name = "CartTool", description = "查看或修改当前登录用户的购物车。operation 只能是 get、add、update、remove；查看购物车必须使用 get，不能使用 list")
    public CartToolResult cart(@ToolParam(description = "购物车操作，不要提交用户ID或操作标识") NativeCartToolRequest request,
                               ToolContext context) {
        if (request == null || request.operation() == null) throw new BusinessException("购物车操作不能为空");
        String operation = request.operation().trim().toLowerCase(java.util.Locale.ROOT);
        // 一个提交中，同一商品的同类写操作只有一个身份，与调用顺序无关。
        // 数量只进入 CartTool 的参数指纹：重试改变数量时冲突，不能绕过去重再次加购。
        // 用户主动追加购买必须提交新的 clientRequestId；userId 由幂等服务单独隔离。
        return cartTool.execute(requireContextUserId(context),
                com.example.ai.tool.support.CartOperationIdentity.request(clientRequestId, operation, request.productId(), request.quantity()));
    }

    @Tool(name = "OrderQueryTool", description = "查询当前登录用户自己的订单。operation=list 时仅传可选的 page、size，不能传 orderId；operation=detail 时必须传 orderId，不能传 page、size。")
    public OrderQueryToolResult orderQuery(@ToolParam(description = "订单查询条件") OrderQueryToolRequest request,
                                           ToolContext context) {
        return orderQueryTool.execute(requireContextUserId(context), request);
    }

    @Tool(name = "OrderPreviewTool", description = "根据当前用户购物车生成不扣库存的订单预览；不能确认订单")
    public OrderDraftResponse orderPreview(ToolContext context) {
        return orderDraftService.preview(requireContextUserId(context));
    }

    private Long requireContextUserId(ToolContext context) {
        if (context == null || context.getContext() == null || !userId.equals(context.getContext().get("userId"))) {
            throw new UnauthorizedException("未登录或登录已失效");
        }
        return userId;
    }

    public record NativeCartToolRequest(String operation, Long productId, Integer quantity) {
    }
}
