package com.example.service.ai.impl;
import com.example.ai.model.response.CartToolResult;
import com.example.ai.model.response.OrderQueryToolResult;
import com.example.ai.model.response.ReadOnlyProductSnapshot;
import com.example.ai.model.response.ReadOnlyToolResult;
import com.example.ai.rag.KnowledgeReference;
import com.example.dto.cart.Response.CartItemResponse;
import com.example.dto.cart.Response.CartResponse;
import com.example.dto.order.Response.OrderItemResponse;
import com.example.dto.order.Response.OrderDraftResponse;
import com.example.dto.order.Response.OrderPreviewItemResponse;
import com.example.dto.order.Response.OrderResponse;
import com.example.dto.order.Response.OrderSummaryResponse;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 根据成功工具事实与明确的业务拒绝校验回复；业务拒绝不能推导价格或库存数量。 */
final class AiGroundingValidator {
    static final String REJECTION_MESSAGE = "部分商品事实暂未确认，请以页面最新信息为准；如需购买，请重新确认订单。";

    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile(
            "(?:商品|产品)\\s*(?:ID|编号)\\s*(?:为|是|[:：#])?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(?:[¥￥]\\s*|(?:价格|售价|到手价|报价|总价|总金额|总额|合计|共计|金额)\\s*(?:为|是|[:：])?\\s*)"
                    + "([\\d,]{1,12}(?:\\.\\d{1,2})?)\\s*(?:元)?");
    private static final Pattern STOCK_NUMBER_PATTERN = Pattern.compile(
            "库存\\s*(?:为|有|剩余|约)?\\s*(\\d+)\\s*(?:件|台)?");

    private static final Pattern STOCK_ASSERTION_PATTERN = Pattern.compile(
            "(?:当前|现在)?(?:有货|现货|缺货|无货|已售罄|已经售罄|可直接购买)"
                    + "|库存\\s*(?:充足|不足|足够|为|有|剩余|已售罄)");
    private static final Pattern RAG_PRICE_PATTERN = Pattern.compile("价格=([\\d,]{1,12}(?:\\.\\d{1,2})?)");
    private static final Pattern RAG_STOCK_PATTERN = Pattern.compile("库存=([\\d]{1,12})");
    private static final Pattern PROMOTION_CLAIM_PATTERN = Pattern.compile(
            "(?:优惠|折扣|满减|立减|补贴|优惠券|赠品)\\s*(?:[¥￥]?\\d|可用|已|享|有)|(?:有|享|提供|支持).{0,8}(?:优惠|折扣|满减|立减|赠品)");
    private static final Pattern ORDER_CREATED_PATTERN = Pattern.compile(
            "已下单|下单成功|订单已创建|订单创建成功|订单已确认|已完成下单|付款成功");
    /**
     * 订单状态断言：要求「状态」「为/是/：」或「已/已经」至少出现一个，避免把「订单支付流程」这类普通表述误判为状态断言。
     * 状态词不写成「已支付」：句中的「已」已被前面的连接词消费，写成「已支付」会漏掉「订单已支付」这种最常见说法。
     */
    private static final Pattern ORDER_STATUS_PATTERN = Pattern.compile(
            "(?:你的|该|此|这笔|当前)?订单\\s*(?:"
                    + "状态\\s*(?:为|是|[:：])?\\s*(?:已经|已)?"
                    + "|(?:为|是|[:：])\\s*(?:已经|已)?"
                    + "|(?:已经|已)\\s*)"
                    + "(?:待支付|支付|取消|完成|关闭|超时关闭|发货|待发货|配送中|签收)");

    private AiGroundingValidator() {
    }

    static GroundingDecision validate(String message, String userMessage, List<Object> successfulToolResults) {
        return validate(message, userMessage, successfulToolResults, List.of());
    }

    static GroundingDecision validate(String message,
                                      String userMessage,
                                      List<Object> successfulToolResults,
                                      List<KnowledgeReference> knowledgeReferences) {
        String normalizedMessage = message == null ? "" : message.trim();
        if (normalizedMessage.isEmpty()) {
            return GroundingDecision.reject("EMPTY_REPLY");
        }
        FactEvidence evidence = FactEvidence.from(successfulToolResults, knowledgeReferences);

        if (ORDER_CREATED_PATTERN.matcher(normalizedMessage).find()) {
            return GroundingDecision.reject("UNSUPPORTED_ORDER_CONFIRMATION");
        }
        if (PROMOTION_CLAIM_PATTERN.matcher(normalizedMessage).find()) {
            return GroundingDecision.reject("UNSUPPORTED_PROMOTION");
        }
        if (containsUnknownProductId(normalizedMessage, evidence.productIds())) {
            return GroundingDecision.reject("UNVERIFIED_PRODUCT");
        }
        // 对明确关联商品 ID 的报价、库存进行商品级核对，避免不同商品之间串用事实。
        if (containsConflictingProductFacts(normalizedMessage, knowledgeReferences)) {
            return GroundingDecision.reject("CONFLICTING_PRODUCT_FACT");
        }
        if (containsUnverifiedAmount(normalizedMessage, userMessage, evidence.amounts())) {
            return GroundingDecision.reject("UNVERIFIED_AMOUNT");
        }
        if (containsUnverifiedInventory(normalizedMessage, evidence)) {
            return GroundingDecision.reject("UNVERIFIED_INVENTORY");
        }
        if (ORDER_STATUS_PATTERN.matcher(normalizedMessage).find() && !evidence.hasOrderStatus()) {
            return GroundingDecision.reject("UNVERIFIED_ORDER_STATUS");
        }
        return GroundingDecision.accept(normalizedMessage);
    }

    private static boolean containsUnknownProductId(String message, Set<Long> productIds) {
        Matcher matcher = PRODUCT_ID_PATTERN.matcher(message);
        while (matcher.find()) {
            try {
                if (!productIds.contains(Long.parseLong(matcher.group(1)))) {
                    return true;
                }
            } catch (NumberFormatException exception) {
                return true;
            }
        }
        return false;
    }

    /** 仅对明确写出商品 ID 的语句使用 product_mysql 的商品级事实；不推测代词所属商品。 */
    private static boolean containsConflictingProductFacts(String message,
                                                            List<KnowledgeReference> references) {
        if (references == null || references.isEmpty()) {
            return false;
        }
        Map<Long, ProductFact> facts = new HashMap<>();
        for (KnowledgeReference reference : references) {
            if (reference == null || reference.productId() == null
                    || !"product_mysql".equals(reference.sourceType())) {
                continue;
            }
            String content = reference.content() == null ? "" : reference.content();
            Matcher priceMatcher = RAG_PRICE_PATTERN.matcher(content);
            Matcher stockMatcher = RAG_STOCK_PATTERN.matcher(content);
            BigDecimal price = priceMatcher.find() ? parseAmount(priceMatcher.group(1)) : null;
            Integer stock = null;
            if (stockMatcher.find()) {
                try {
                    stock = Integer.valueOf(stockMatcher.group(1));
                } catch (NumberFormatException ignored) {
                    // 无效库存不能作为可信依据。
                }
            }
            facts.put(reference.productId(), new ProductFact(price, stock));
        }
        Matcher idMatcher = PRODUCT_ID_PATTERN.matcher(message);
        List<ProductMention> mentions = new ArrayList<>();
        while (idMatcher.find()) {
            try {
                mentions.add(new ProductMention(Long.parseLong(idMatcher.group(1)),
                        idMatcher.start(), idMatcher.end()));
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        for (int i = 0; i < mentions.size(); i++) {
            ProductMention mention = mentions.get(i);
            ProductFact fact = facts.get(mention.productId());
            if (fact == null) {
                continue;
            }
            int end = i + 1 < mentions.size() ? mentions.get(i + 1).start() : message.length();
            String claim = message.substring(mention.end(), end);
            Matcher priceMatcher = MONEY_PATTERN.matcher(claim);
            while (priceMatcher.find()) {
                BigDecimal statedPrice = parseAmount(priceMatcher.group(1));
                if (statedPrice == null || fact.price() == null
                        || statedPrice.compareTo(fact.price()) != 0) {
                    return true;
                }
            }
            Matcher stockMatcher = STOCK_NUMBER_PATTERN.matcher(claim);
            while (stockMatcher.find()) {
                try {
                    int statedStock = Integer.parseInt(stockMatcher.group(1));
                    if (fact.stock() == null || statedStock != fact.stock()) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private record ProductMention(Long productId, int start, int end) {}

    private record ProductFact(BigDecimal price, Integer stock) {}

    private static boolean containsUnverifiedAmount(String message, String userMessage, Set<BigDecimal> amounts) {
        Set<BigDecimal> userAmounts = extractAmounts(userMessage);
        Matcher matcher = MONEY_PATTERN.matcher(message);
        while (matcher.find()) {
            BigDecimal amount = parseAmount(matcher.group(1));
            if (amount == null || (!containsAmount(amounts, amount) && !containsAmount(userAmounts, amount))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnverifiedInventory(String message, FactEvidence evidence) {
        Matcher numberMatcher = STOCK_NUMBER_PATTERN.matcher(message);
        while (numberMatcher.find()) {
            try {
                if (!evidence.stocks().contains(Integer.parseInt(numberMatcher.group(1)))) {
                    return true;
                }
            } catch (NumberFormatException exception) {
                return true;
            }
        }
        if (!STOCK_ASSERTION_PATTERN.matcher(message).find()) {
            return false;
        }
        if ((message.contains("有货") && !message.contains("没有货")) || message.contains("现货") || message.contains("可直接购买")
                || message.contains("库存充足") || message.contains("库存足够")) {
            return !evidence.hasAvailableStock();
        }
        if (message.contains("缺货") || message.contains("无货") || message.contains("没有货") || message.contains("已售罄")) {
            return !evidence.hasUnavailableStock();
        }
        if (message.contains("库存不足")) {
            if (evidence.hasUnavailableStock()) return false;
            // 所需数量不足不等于零库存；只支持原操作涉及的商品，不向其他商品转移依据。
            if (evidence.insufficientProductIds().isEmpty()) return true;
            Matcher mentions = PRODUCT_ID_PATTERN.matcher(message);
            while (mentions.find()) {
                if (!evidence.insufficientProductIds().contains(Long.parseLong(mentions.group(1)))) return true;
            }
            return false;
        }
        return evidence.stocks().isEmpty();
    }

    private static Set<BigDecimal> extractAmounts(String text) {
        Set<BigDecimal> amounts = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return amounts;
        }
        Matcher matcher = MONEY_PATTERN.matcher(text);
        while (matcher.find()) {
            BigDecimal amount = parseAmount(matcher.group(1));
            if (amount != null) {
                amounts.add(amount);
            }
        }
        return amounts;
    }

    private static BigDecimal parseAmount(String value) {
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean containsAmount(Set<BigDecimal> amounts, BigDecimal value) {
        return amounts.stream().anyMatch(item -> item.compareTo(value) == 0);
    }

    record GroundingDecision(boolean accepted, String message, String failureType) {
        private static GroundingDecision accept(String message) {
            return new GroundingDecision(true, message, "NONE");
        }

        private static GroundingDecision reject(String failureType) {
            return new GroundingDecision(false, REJECTION_MESSAGE, failureType);
        }
    }

    private record FactEvidence(Set<Long> productIds,
                                Set<BigDecimal> amounts,
                                Set<Integer> stocks,
                                Set<Long> insufficientProductIds,
                                boolean hasAvailableStock,
                                boolean hasUnavailableStock,
                                boolean hasOrderStatus) {
        private static FactEvidence from(List<Object> successfulToolResults,
                                         List<KnowledgeReference> knowledgeReferences) {
            Set<Long> productIds = new LinkedHashSet<>();
            Set<BigDecimal> amounts = new LinkedHashSet<>();
            Set<Integer> stocks = new LinkedHashSet<>();
            Set<Long> insufficientProductIds = new LinkedHashSet<>();
            boolean hasAvailableStock = false;
            boolean hasUnavailableStock = false;
            boolean hasOrderStatus = false;
            for (Object result : successfulToolResults == null ? List.of() : successfulToolResults) {
                if (result instanceof ReadOnlyToolResult readOnly && readOnly.hasUsableFacts()) {
                    for (ReadOnlyProductSnapshot product : readOnly.products()) {
                        if (product.productId() != null && product.onSale()) {
                            productIds.add(product.productId());
                        }
                        if (product.price() != null) {
                            amounts.add(product.price());
                        }
                        if (product.onSale() && product.stock() != null) {
                            stocks.add(product.stock());
                            hasAvailableStock = hasAvailableStock || product.available() && product.stock() > 0;
                            hasUnavailableStock = hasUnavailableStock || !product.available() || product.stock() <= 0;
                        }
                    }
                } else if (result instanceof CartToolResult cart && cart.success()) {
                    // 记录本次购物车操作涉及的商品ID
                    if (cart.changedProductId() != null) {
                        productIds.add(cart.changedProductId());
                    }
                    CartResponse cartResponse = cart.cart();
                    if (cartResponse != null) {
                        addAmount(amounts, cartResponse.displayTotalAmount());
                        for (CartItemResponse item : cartResponse.items() == null ? List.<CartItemResponse>of() : cartResponse.items()) {
                            addAmount(amounts, item.displayPrice());
                            addAmount(amounts, item.displaySubtotal());
                            if (item.productId() != null && item.status() != null && item.status() == 1) {
                                productIds.add(item.productId());
                            }
                            if (item.stock() != null) {
                                stocks.add(item.stock());
                                hasAvailableStock = hasAvailableStock || item.status() != null && item.status() == 1 && item.stock() > 0;
                                hasUnavailableStock = hasUnavailableStock || item.stock() <= 0;
                            }
                        }
                    }
                } else if (result instanceof CartToolResult cart && !cart.success()
                        && "BUSINESS_FAILURE".equals(cart.failureType())
                        && "商品库存不足".equals(cart.message())
                        && ("add".equals(cart.operation()) || "update".equals(cart.operation()))
                        && cart.changedProductId() != null) {
                    // 仅识别业务服务确定返回的拒绝，系统异常、任意失败文本不能充当事实。
                    insufficientProductIds.add(cart.changedProductId());
                    productIds.add(cart.changedProductId());
                } else if (result instanceof OrderQueryToolResult orderQuery && orderQuery.success()) {
                    hasOrderStatus = hasOrderStatus || orderQuery.order() != null || !orderQuery.orders().isEmpty();
                    addOrderFacts(productIds, amounts, orderQuery.order());
                    for (OrderSummaryResponse order : orderQuery.orders()) {
                        addAmount(amounts, order.totalAmount());
                    }
                } else if (result instanceof OrderDraftResponse draft) {
                    addAmount(amounts, draft.previewTotalAmount());
                    for (OrderPreviewItemResponse item : draft.items()) {
                        if (item.productId() != null) {
                            productIds.add(item.productId());
                        }
                        addAmount(amounts, item.previewUnitPrice());
                        addAmount(amounts, item.previewSubtotal());
                    }
                }
            }
            for (KnowledgeReference reference : knowledgeReferences == null ? List.<KnowledgeReference>of() : knowledgeReferences) {
                if (reference.productId() == null || !"product_mysql".equals(reference.sourceType())) {
                    continue;
                }
                productIds.add(reference.productId());
                String content = reference.content() == null ? "" : reference.content();
                Matcher priceMatcher = RAG_PRICE_PATTERN.matcher(content);
                while (priceMatcher.find()) {
                    BigDecimal amount = parseAmount(priceMatcher.group(1));
                    if (amount != null) {
                        amounts.add(amount);
                    }
                }
                Matcher stockMatcher = RAG_STOCK_PATTERN.matcher(content);
                while (stockMatcher.find()) {
                    try {
                        int stock = Integer.parseInt(stockMatcher.group(1));
                        stocks.add(stock);
                        hasAvailableStock = hasAvailableStock || stock > 0;
                        hasUnavailableStock = hasUnavailableStock || stock <= 0;
                    } catch (NumberFormatException ignored) {
                        // 忽略格式异常的向量上下文，保留其他已确认事实。
                    }
                }
            }
            return new FactEvidence(Set.copyOf(productIds), Set.copyOf(amounts), Set.copyOf(stocks),
                    Set.copyOf(insufficientProductIds), hasAvailableStock, hasUnavailableStock, hasOrderStatus);
        }

        private static void addOrderFacts(Set<Long> productIds, Set<BigDecimal> amounts, OrderResponse order) {
            if (order == null) {
                return;
            }
            addAmount(amounts, order.totalAmount());
            for (OrderItemResponse item : order.items() == null ? List.<OrderItemResponse>of() : order.items()) {
                if (item.productId() != null) {
                    productIds.add(item.productId());
                }
                addAmount(amounts, item.unitPrice());
                addAmount(amounts, item.subtotal());
            }
        }

        private static void addAmount(Set<BigDecimal> amounts, BigDecimal amount) {
            if (amount != null) {
                amounts.add(amount);
            }
        }
    }
}
