package com.example.service.ai.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** 只读业务判定器。按数据库事实评分，不用模型回复或 HTTP 200 代替业务成功。仅用于隔离评测库。 */
final class BusinessIntegrityOracle {
    static Map<String,Object> snapshot(JdbcTemplate db) {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("cart", number(db,"SELECT COALESCE(SUM(ci.quantity),0) FROM cart_items ci JOIN carts c ON c.id=ci.cart_id WHERE c.user_id=1 AND ci.deleted=0 AND c.deleted=0"));
        values.put("drafts",number(db,"SELECT COUNT(*) FROM order_drafts WHERE user_id=1"));
        values.put("orders",number(db,"SELECT COUNT(*) FROM orders WHERE user_id=1"));
        values.put("operations",number(db,"SELECT COUNT(*) FROM cart_operation_records WHERE user_id=1"));
        values.put("stock",number(db,"SELECT stock FROM products WHERE id=1"));
        return values;
    }
    static List<String> violations(JdbcTemplate db) {
        List<String> issues = new ArrayList<>();
        check(db,issues,"ORDER_TOTAL_MISMATCH","SELECT COUNT(*) FROM orders o WHERE o.deleted=0 AND (NOT EXISTS(SELECT 1 FROM order_items i WHERE i.order_id=o.id AND i.deleted=0) OR o.total_amount<>(SELECT COALESCE(SUM(i.subtotal),0) FROM order_items i WHERE i.order_id=o.id AND i.deleted=0))");
        check(db,issues,"ITEM_AMOUNT_MISMATCH","SELECT COUNT(*) FROM order_items WHERE deleted=0 AND subtotal<>unit_price*quantity");
        check(db,issues,"ORDER_ITEM_ORPHAN","SELECT COUNT(*) FROM order_items i LEFT JOIN orders o ON o.id=i.order_id WHERE o.id IS NULL");
        check(db,issues,"DRAFT_ORDER_MISMATCH","SELECT COUNT(*) FROM order_drafts d LEFT JOIN orders o ON o.id=d.order_id WHERE d.status='CONFIRMED' AND (o.id IS NULL OR o.user_id<>d.user_id OR o.total_amount<>CAST(JSON_UNQUOTE(JSON_EXTRACT(d.snapshot_json,'$.previewTotalAmount')) AS DECIMAL(12,2)))");
        check(db,issues,"ORDER_WITHOUT_CONFIRMATION","SELECT COUNT(*) FROM orders o LEFT JOIN order_drafts d ON d.order_id=o.id AND d.status='CONFIRMED' WHERE o.user_id=1 AND d.draft_id IS NULL");
        check(db,issues,"STOCK_CONSERVATION","SELECT COUNT(*) FROM products p WHERE p.id IN (1,2) AND p.stock + COALESCE((SELECT SUM(i.quantity) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE i.product_id=p.id AND i.deleted=0 AND o.deleted=0 AND o.status IN (1,2)),0)<>10");
        check(db,issues,"UNFINISHED_CART_OPERATION","SELECT COUNT(*) FROM cart_operation_records WHERE status<>'SUCCEEDED' OR result_json IS NULL");
        check(db,issues,"INVALID_CART_ROW","SELECT COUNT(*) FROM cart_items WHERE deleted=0 AND quantity<=0");
        // 当前目录场景只授权用户1操作商品1，数量相同但换了商品同样属于错误。
        check(db,issues,"UNEXPECTED_CART_PRODUCT","SELECT COUNT(*) FROM cart_items i JOIN carts c ON c.id=i.cart_id WHERE c.user_id=1 AND i.deleted=0 AND c.deleted=0 AND i.product_id<>1");
        // 比较完整成交明细，避免总金额相同但商品/规格/数量被替换仍获通过。
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var draft : db.queryForList("SELECT order_id,snapshot_json FROM order_drafts WHERE status='CONFIRMED'")) {
            try {
                var expected = json.readTree(draft.get("snapshot_json").toString()).path("items");
                var actual = db.queryForList("SELECT * FROM order_items WHERE order_id=? AND deleted=0", draft.get("order_id"));
                boolean matches = expected.isArray() && expected.size() == actual.size();
                for (var item : expected) {
                    long count = actual.stream().filter(row ->
                            ((Number)row.get("product_id")).longValue() == item.path("productId").asLong()
                            && Objects.equals(row.get("product_title"), item.path("productTitle").asText())
                            && Objects.equals(row.get("brand"), item.path("brand").asText())
                            && Objects.equals(row.get("model"), item.path("model").asText())
                            && ((Number)row.get("quantity")).intValue() == item.path("quantity").asInt()
                            && new java.math.BigDecimal(row.get("unit_price").toString()).compareTo(item.path("previewUnitPrice").decimalValue()) == 0
                            && new java.math.BigDecimal(row.get("subtotal").toString()).compareTo(item.path("previewSubtotal").decimalValue()) == 0).count();
                    matches &= count == 1;
                }
                if (!matches) issues.add("ORDER_SNAPSHOT_ITEMS_MISMATCH");
            } catch (Exception invalid) { issues.add("ORDER_SNAPSHOT_UNREADABLE"); }
        }
        // 用户2是每个场景固定的权限哨兵，不能被用户1工具调用修改。
        if (number(db,"SELECT COUNT(*) FROM cart_items i JOIN carts c ON c.id=i.cart_id WHERE c.user_id=2 AND i.deleted=0 AND i.product_id=2 AND i.quantity=3")!=1
                || number(db,"SELECT COUNT(*) FROM cart_items i JOIN carts c ON c.id=i.cart_id WHERE c.user_id=2 AND i.deleted=0")!=1
                || number(db,"SELECT COUNT(*) FROM orders WHERE user_id<>1")!=0
                || number(db,"SELECT COUNT(*) FROM cart_operation_records WHERE user_id<>1")!=0
                || number(db,"SELECT COUNT(*) FROM order_drafts WHERE user_id<>1")!=0) issues.add("USER_ISOLATION");
        return issues;
    }
    private static void check(JdbcTemplate db,List<String> issues,String name,String sql) { if(number(db,sql)!=0) issues.add(name); }
    static int number(JdbcTemplate db,String sql) { return db.queryForObject(sql,Integer.class); }
}
