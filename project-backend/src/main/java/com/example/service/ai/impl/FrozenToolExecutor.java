package com.example.service.ai.impl;

import com.example.ai.model.request.InventoryToolRequest;
import com.example.ai.model.request.OrderQueryToolRequest;
import com.example.ai.tool.*;
import com.example.ai.rag.HybridRagService;
import com.example.ai.tool.support.CartOperationIdentity;
import com.example.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

/** 只运行保存的白名单步骤，不调用 LLM，不产生新的执行计划，不包含订单预览/确认/支付。 */
@Component
public class FrozenToolExecutor {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> ALLOWED = Set.of("CartTool", "ProductDetailTool", "InventoryTool", "OrderQueryTool", "HybridRagTool");
    @Resource private CartTool cartTool;
    @Resource private ProductDetailTool productDetailTool;
    @Resource private InventoryTool inventoryTool;
    @Resource private OrderQueryTool orderQueryTool;
    @Resource private HybridRagService hybridRagService;

    static boolean allowed(String tool) { return ALLOWED.contains(tool); }

    public Object execute(Long user, String client, String tool, String arguments, List<Object> previousFacts) {
        if (!allowed(tool)) throw new BusinessException("该工具不支持原步骤恢复");
        try {
            var args = JSON.readTree(arguments);
            return switch (tool) {
                case "CartTool" -> cartTool.execute(user, CartOperationIdentity.fromArguments(client, arguments));
                case "ProductDetailTool" -> productDetailTool.detail(JSON.treeToValue(args.required("productId"), Long.class));
                case "InventoryTool" -> {
                    var request = JSON.treeToValue(args.required("request"), InventoryToolRequest.class);
                    var audit = new NativeToolAudit("recovery", null, null, null);
                    previousFacts.forEach(audit::seedRecoveredFacts);
                    audit.requireKnownProductIds("InventoryTool", request.productIds());
                    yield inventoryTool.inventory(request);
                }
                case "OrderQueryTool" -> orderQueryTool.execute(user, JSON.treeToValue(args.required("request"), OrderQueryToolRequest.class));
                case "HybridRagTool" -> {
                    String query = JSON.treeToValue(args.required("query"), String.class);
                    if (query == null || query.isBlank()) throw new BusinessException("检索问题不能为空");
                    yield hybridRagService.retrieve(query.trim());
                }
                default -> throw new BusinessException("该工具不支持恢复");
            };
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException malformed) {
            throw new BusinessException("保存的工具参数无效，不能恢复执行");
        }
    }
}
