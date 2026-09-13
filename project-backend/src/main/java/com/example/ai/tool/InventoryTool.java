package com.example.ai.tool;

import com.example.ai.model.request.InventoryToolRequest;
import com.example.ai.model.response.ReadOnlyProductSnapshot;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.model.response.ReadOnlyToolResultStatus;
import com.example.ai.tool.support.ReadOnlyToolSupport;
import com.example.entity.ProductEntity;
import com.example.exception.BusinessException;
import com.example.service.product.ProductService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 读取指定商品的实时库存与上架状态。 */
@Service
public class InventoryTool {
    @Resource
    private ProductService productService;

    @Transactional(readOnly = true)
    public ReadOnlyToolResult inventory(InventoryToolRequest request) {
        InventoryToolRequest normalized = normalize(request);
        List<ReadOnlyProductSnapshot> products = new ArrayList<>();
        List<Long> matchedProductIds = new ArrayList<>();
        for (Long productId : normalized.productIds()) {
            ProductEntity product = productService.findById(productId);
            if (product == null) {
                products.add(ReadOnlyToolSupport.missing(productId, "商品不存在或已删除"));
                continue;
            }
            boolean onSale = ReadOnlyToolSupport.isOnSale(product.getStatus());
            boolean stockEnough = ReadOnlyToolSupport.hasStock(product.getStock(), normalized.requiredQuantity());
            boolean available = onSale && stockEnough;
            List<String> matchedFacts = new ArrayList<>();
            List<String> missingFacts = new ArrayList<>();
            ReadOnlyToolSupport.appendConditionFact(matchedFacts, missingFacts, onSale, "商品当前在售", "商品已下架");
            ReadOnlyToolSupport.appendConditionFact(matchedFacts, missingFacts, stockEnough, "库存满足需求", "库存不足");
            products.add(ReadOnlyToolSupport.from(
                    product,
                    available,
                    available,
                    normalized.requiredQuantity(),
                    null,
                    available ? "库存满足需求" : "库存不足",
                    matchedFacts,
                    missingFacts));
            if (available) {
                matchedProductIds.add(product.getId());
            }
        }
        boolean anyExistingProduct = products.stream().anyMatch(product -> product.status() != null);
        boolean allAvailable = anyExistingProduct && products.stream().allMatch(ReadOnlyProductSnapshot::available);
        ReadOnlyToolResultStatus resultStatus = !anyExistingProduct
                ? ReadOnlyToolResultStatus.BUSINESS_FAILURE
                : allAvailable
                ? ReadOnlyToolResultStatus.SUCCESS
                : ReadOnlyToolResultStatus.PARTIAL_SUCCESS;
        String message = resultStatus == ReadOnlyToolResultStatus.SUCCESS
                ? "找到 " + matchedProductIds.size() + " 个库存可用商品"
                : "当前没有库存可用的商品";
        if (resultStatus == ReadOnlyToolResultStatus.PARTIAL_SUCCESS) {
            message = matchedProductIds.isEmpty()
                    ? "商品存在，但当前无可用库存或部分商品不可售"
                    : "找到 " + matchedProductIds.size() + " 个库存可用商品，其他商品当前不可用";
        }
        return ReadOnlyToolSupport.result("InventoryTool", resultStatus, message, products, matchedProductIds, products.size());
    }

    private InventoryToolRequest normalize(InventoryToolRequest request) {
        if (request == null) {
            throw new BusinessException("库存查询参数不能为空");
        }
        List<Long> productIds = ReadOnlyToolSupport.normalizeProductIds(request.productIds(), ReadOnlyToolSupport.MAX_PRODUCT_IDS, "商品ID列表");
        Integer requiredQuantity = request.requiredQuantity();
        if (requiredQuantity == null) {
            requiredQuantity = 1;
        }
        if (requiredQuantity < 1) {
            throw new BusinessException("需求数量必须大于0");
        }
        if (requiredQuantity > ReadOnlyToolSupport.MAX_REQUIRED_QUANTITY) {
            throw new BusinessException("需求数量不能超过" + ReadOnlyToolSupport.MAX_REQUIRED_QUANTITY);
        }
        return new InventoryToolRequest(productIds, requiredQuantity);
    }
}
