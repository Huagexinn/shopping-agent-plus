package com.example.ai.tool;

import com.example.ai.model.response.ReadOnlyProductSnapshot;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.model.response.ReadOnlyToolResultStatus;
import com.example.ai.tool.support.ReadOnlyToolSupport;
import com.example.dto.product.Response.ProductResponse;
import com.example.exception.BusinessException;
import com.example.service.product.ProductService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 读取单个公开商品的完整事实快照。 */
@Service
public class ProductDetailTool {
    @Resource
    private ProductService productService;

    @Transactional(readOnly = true)
    public ReadOnlyToolResult detail(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException("商品ID必须是正整数");
        }
        ProductResponse product = productService.findPublicById(productId);
        if (product == null) {
            return ReadOnlyToolSupport.result(
                    "ProductDetailTool",
                    false,
                    "商品不存在、已下架或不可公开访问",
                    List.of(),
                    List.of(),
                    0);
        }
        boolean specsComplete = ReadOnlyToolSupport.hasSpecs(product.ramGb(), product.storageGb());
        boolean available = ReadOnlyToolSupport.hasStock(product.stock(), 1);
        List<String> missingFacts = ReadOnlyToolSupport.buildMissingSpecsFacts(
                product.ramGb(),
                product.storageGb(),
                "内存或存储规格未录入");
        ReadOnlyProductSnapshot snapshot = ReadOnlyToolSupport.from(
                product,
                true,
                available,
                null,
                null,
                specsComplete ? "商品资料完整" : "商品资料不足，缺少内存或存储规格",
                ReadOnlyToolSupport.buildFactList(product),
                missingFacts);
        ReadOnlyToolResultStatus resultStatus = specsComplete
                ? ReadOnlyToolResultStatus.SUCCESS : ReadOnlyToolResultStatus.PARTIAL_SUCCESS;
        String message = specsComplete ? "已读取商品公开详情" : "商品资料不足，缺少内存或存储规格";
        return ReadOnlyToolSupport.result("ProductDetailTool", resultStatus, message,
                List.of(snapshot), List.of(product.id()), 1);
    }
}
