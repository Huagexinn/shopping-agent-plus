package com.example.ai.tool.support;

import com.example.dto.product.Response.ProductResponse;
import com.example.entity.ProductEntity;
import com.example.exception.BusinessException;
import com.example.ai.model.response.ReadOnlyProductSnapshot;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.model.response.ReadOnlyToolResultStatus;
import com.example.utils.Constants;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/** 只读 Tool 的公共参数校验与事实快照转换。 */
public final class ReadOnlyToolSupport {
    public static final int MAX_PRODUCT_IDS = 50;
    public static final int MAX_REQUIRED_QUANTITY = 1000;

    private ReadOnlyToolSupport() {
    }

    public static List<Long> normalizeProductIds(List<Long> productIds, int maxSize, String fieldLabel) {
        if (productIds == null || productIds.isEmpty()) {
            throw new BusinessException(fieldLabel + "不能为空");
        }
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (Long productId : productIds) {
            if (productId == null || productId <= 0) {
                throw new BusinessException(fieldLabel + "中的商品ID必须是正整数");
            }
            uniqueIds.add(productId);
        }
        if (uniqueIds.size() > maxSize) {
            throw new BusinessException(fieldLabel + "不能超过" + maxSize + "个");
        }
        return List.copyOf(uniqueIds);
    }

    public static ReadOnlyToolResult result(String toolName,
                                            boolean success,
                                            String message,
                                            List<ReadOnlyProductSnapshot> products,
                                            List<Long> productIds,
                                            long total) {
        return result(toolName,
                success ? ReadOnlyToolResultStatus.SUCCESS : ReadOnlyToolResultStatus.BUSINESS_FAILURE,
                message, products, productIds, total);
    }

    public static ReadOnlyToolResult result(String toolName,
                                            ReadOnlyToolResultStatus resultStatus,
                                            String message,
                                            List<ReadOnlyProductSnapshot> products,
                                            List<Long> productIds,
                                            long total) {
        return new ReadOnlyToolResult(toolName,
                resultStatus == ReadOnlyToolResultStatus.SUCCESS
                        || resultStatus == ReadOnlyToolResultStatus.PARTIAL_SUCCESS,
                message, products, productIds, total, LocalDateTime.now(), resultStatus);
    }

    public static ReadOnlyProductSnapshot from(ProductResponse product,
                                               boolean matched,
                                               boolean available,
                                               Integer requiredQuantity,
                                               Integer rank,
                                               String reason,
                                               List<String> matchedFacts,
                                               List<String> missingFacts) {
        if (product == null) {
            return missing(null, reason);
        }
        return createSnapshot(
                product.id(),
                product.brand(),
                product.model(),
                product.title(),
                product.description(),
                product.price(),
                product.stock(),
                product.status(),
                product.ramGb(),
                product.storageGb(),
                matched,
                available,
                requiredQuantity,
                rank,
                reason,
                matchedFacts,
                missingFacts);
    }

    public static ReadOnlyProductSnapshot from(ProductEntity product,
                                               boolean matched,
                                               boolean available,
                                               Integer requiredQuantity,
                                               Integer rank,
                                               String reason,
                                               List<String> matchedFacts,
                                               List<String> missingFacts) {
        if (product == null) {
            return missing(null, reason);
        }
        return createSnapshot(
                product.getId(),
                product.getBrand(),
                product.getModel(),
                product.getTitle(),
                product.getDescription(),
                product.getPrice(),
                product.getStock(),
                product.getStatus(),
                product.getRamGb(),
                product.getStorageGb(),
                matched,
                available,
                requiredQuantity,
                rank,
                reason,
                matchedFacts,
                missingFacts);
    }

    public static ReadOnlyProductSnapshot missing(Long productId, String reason) {
        return createSnapshot(productId, null, null, null, null, null, null, null, null, null,
                false, false, null, null, reason, List.of(), reason == null ? List.of() : List.of(reason));
    }

    public static boolean hasSpecs(Integer ramGb, Integer storageGb) {
        return ramGb != null && ramGb > 0 && storageGb != null && storageGb > 0;
    }

    public static boolean isOnSale(Integer status) {
        return status != null && status == Constants.ON_SALE;
    }

    public static boolean hasStock(Integer stock, int quantity) {
        return stock != null && quantity > 0 && stock >= quantity;
    }

    public static void appendConditionFact(List<String> matchedFacts,
                                           List<String> missingFacts,
                                           boolean matched,
                                           String matchedFact,
                                           String missingFact) {
        if (matched) {
            matchedFacts.add(matchedFact);
        } else {
            missingFacts.add(missingFact);
        }
    }

    public static List<String> buildFactList(ProductResponse product) {
        return buildFactList(product.price(), product.stock(), product.ramGb(), product.storageGb());
    }

    public static List<String> buildMissingSpecsFacts(Integer ramGb, Integer storageGb, String missingFact) {
        if (hasSpecs(ramGb, storageGb)) {
            return List.of();
        }
        return List.of(missingFact);
    }

    private static List<String> buildFactList(BigDecimal price, Integer stock, Integer ramGb, Integer storageGb) {
        List<String> facts = new ArrayList<>();
        appendIfPresent(facts, price, value -> "价格 ¥" + value);
        appendIfPresent(facts, stock, value -> "库存 " + value + " 件");
        appendIfPositive(facts, ramGb, value -> value + "GB 内存");
        appendIfPositive(facts, storageGb, value -> value + "GB 存储");
        return facts;
    }

    private static <T> void appendIfPresent(List<String> facts, T value, Function<T, String> formatter) {
        if (value != null) {
            facts.add(formatter.apply(value));
        }
    }

    private static void appendIfPositive(List<String> facts, Integer value, Function<Integer, String> formatter) {
        if (value != null && value > 0) {
            facts.add(formatter.apply(value));
        }
    }

    private static ReadOnlyProductSnapshot createSnapshot(Long productId,
                                                          String brand,
                                                          String model,
                                                          String title,
                                                          String description,
                                                          java.math.BigDecimal price,
                                                          Integer stock,
                                                          Integer status,
                                                          Integer ramGb,
                                                          Integer storageGb,
                                                          boolean matched,
                                                          boolean available,
                                                          Integer requiredQuantity,
                                                          Integer rank,
                                                          String reason,
                                                          List<String> matchedFacts,
                                                          List<String> missingFacts) {
        return new ReadOnlyProductSnapshot(
                productId,
                brand,
                model,
                title,
                description,
                price,
                stock,
                status,
                ramGb,
                storageGb,
                isOnSale(status),
                hasSpecs(ramGb, storageGb),
                matched,
                available,
                requiredQuantity,
                rank,
                reason,
                matchedFacts,
                missingFacts);
    }
}
