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

/**
 * 读取指定商品的实时库存与上架状态。
 */
@Service
public class InventoryTool {

    @Resource
    private ProductService productService;

    @Transactional(readOnly = true)
    public ReadOnlyToolResult inventory(InventoryToolRequest request) {

        InventoryToolRequest normalized = normalize(request);

        List<ReadOnlyProductSnapshot> products = new ArrayList<>();
        List<Long> foundProductIds = new ArrayList<>();

        // 判断所有商品的库存及上架信息是否完整
        boolean allFactsComplete = true;

        for (Long productId : normalized.productIds()) {

            ProductEntity product = productService.findById(productId);

            // 商品不存在
            if (product == null) {
                products.add(ReadOnlyToolSupport.missing(
                        productId, "商品不存在或已删除"
                ));
                allFactsComplete = false;
                continue;
            }

            // 商品存在，记录商品ID，不受库存影响
            foundProductIds.add(product.getId());

            Integer status = product.getStatus();
            Integer stock = product.getStock();

            // 判断商品状态
            boolean onSale = ReadOnlyToolSupport.isOnSale(status);

            // 判断库存是否充足
            boolean stockKnown = stock != null;
            boolean stockEnough = ReadOnlyToolSupport.hasStock(
                    stock, normalized.requiredQuantity()
            );

            // 是否满足购买条件
            boolean available = onSale && stockEnough;

            List<String> matchedFacts = new ArrayList<>();
            List<String> missingFacts = new ArrayList<>();

            // 记录真实的商品上架状态
            if (status == null) {
                missingFacts.add("商品上架状态未录入");
            } else {
                matchedFacts.add(
                        onSale ? "商品当前在售" : "商品当前已下架"
                );
            }

            // 记录真实库存事实
            if (!stockKnown) {
                missingFacts.add("库存信息未录入");
            } else {
                matchedFacts.add("当前库存 " + stock + " 件");
                matchedFacts.add(
                        stockEnough ? "库存满足需求" : "库存不足"
                );
            }

            // 只有真实数据缺失才标记为信息不完整
            if (status == null || stock == null) {
                allFactsComplete = false;
            }

            // 构建商品状态说明
            String reason;

            if (status == null && stock == null) {
                reason = "商品上架状态及库存信息缺失";
            } else if (status == null) {
                reason = "商品上架状态缺失";
            } else if (!onSale && stock == null) {
                reason = "商品已下架，库存信息缺失";
            } else if (!onSale) {
                reason = "商品已下架";
            } else if (stock == null) {
                reason = "库存信息缺失";
            } else if (!stockEnough) {
                reason = "库存不足";
            } else {
                reason = "库存满足需求";
            }

            products.add(ReadOnlyToolSupport.from(
                    product,
                    true,                       // 商品已成功匹配
                    available,                  // 是否满足购买条件
                    normalized.requiredQuantity(),
                    null,
                    reason,
                    matchedFacts,
                    missingFacts
            ));
        }

        // 统计查询结果
        boolean allProductsFound =
                foundProductIds.size() == normalized.productIds().size();

        ReadOnlyToolResultStatus resultStatus;

        if (foundProductIds.isEmpty()) {
            resultStatus = ReadOnlyToolResultStatus.BUSINESS_FAILURE;
        } else if (!allProductsFound || !allFactsComplete) {
            resultStatus = ReadOnlyToolResultStatus.PARTIAL_SUCCESS;
        } else {
            resultStatus = ReadOnlyToolResultStatus.SUCCESS;
        }

        String message;

        if (resultStatus == ReadOnlyToolResultStatus.SUCCESS) {
            message = "成功查询 " + foundProductIds.size() + " 个商品的库存信息";
        } else if (resultStatus == ReadOnlyToolResultStatus.PARTIAL_SUCCESS) {
            message = "查询到 " + foundProductIds.size()
                    + " 个商品，但部分商品不存在或库存、上架信息不完整";
        } else {
            message = "未查询到有效商品";
        }

        return ReadOnlyToolSupport.result(
                "InventoryTool",
                resultStatus,
                message,
                products,
                foundProductIds,
                foundProductIds.size()
        );
    }

    private InventoryToolRequest normalize(InventoryToolRequest request) {

        if (request == null) {
            throw new BusinessException("库存查询参数不能为空");
        }

        List<Long> productIds = ReadOnlyToolSupport.normalizeProductIds(
                request.productIds(),
                ReadOnlyToolSupport.MAX_PRODUCT_IDS,
                "商品ID列表"
        );

        Integer requiredQuantity = request.requiredQuantity();

        if (requiredQuantity == null) {
            requiredQuantity = 1;
        }

        if (requiredQuantity < 1) {
            throw new BusinessException("需求数量必须大于0");
        }

        if (requiredQuantity > ReadOnlyToolSupport.MAX_REQUIRED_QUANTITY) {
            throw new BusinessException(
                    "需求数量不能超过"
                            + ReadOnlyToolSupport.MAX_REQUIRED_QUANTITY
            );
        }

        return new InventoryToolRequest(productIds, requiredQuantity);
    }
}
