package com.example.service.ai.impl;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 负对照：故意制造合法 SQL 可写入的坏状态，验证评分器不会把接口成功当作业务正确。 */
@EnabledIfEnvironmentVariable(named="SHOPPING_TEST_MYSQL_URL",matches=".+")
class BusinessIntegrityOracleIT {
    @BeforeAll static void setup() throws Exception { OrderConfirmationMysqlIT.setup(); }
    @BeforeEach void reset() { AgentEvaluationFixture.reset(2); }
    @AfterAll static void cleanup() { OrderConfirmationMysqlIT.cleanup(); }
    private void confirmed() {
        var d=OrderConfirmationMysqlIT.drafts.preview(1L);
        OrderConfirmationMysqlIT.orders.confirmDraft(1L,d.draftId(),"oracle-confirm",d.fingerprint());
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).isEmpty());
    }
    @Test void detectsOrderTotalEvenWhenOrderExists() {
        confirmed(); OrderConfirmationMysqlIT.jdbc.update("UPDATE orders SET total_amount=199");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("ORDER_TOTAL_MISMATCH"));
    }
    @Test void detectsWrongItemSubtotal() {
        confirmed(); OrderConfirmationMysqlIT.jdbc.update("UPDATE order_items SET subtotal=199");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("ITEM_AMOUNT_MISMATCH"));
    }
    @Test void detectsChangedItemDescriptionEvenWhenTotalIsCorrect() {
        confirmed(); OrderConfirmationMysqlIT.jdbc.update("UPDATE order_items SET product_title='错误商品'");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("ORDER_SNAPSHOT_ITEMS_MISMATCH"));
    }
    @Test void detectsDoubleStockDeduction() {
        confirmed(); OrderConfirmationMysqlIT.jdbc.update("UPDATE products SET stock=6 WHERE id=1");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("STOCK_CONSERVATION"));
    }
    @Test void detectsAnotherUsersCartWrite() {
        OrderConfirmationMysqlIT.jdbc.update("UPDATE cart_items SET quantity=4 WHERE cart_id=2");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("USER_ISOLATION"));
    }
    @Test void detectsWrongCartProductWithSameQuantity() {
        OrderConfirmationMysqlIT.jdbc.update("UPDATE cart_items SET product_id=2 WHERE cart_id=1");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("UNEXPECTED_CART_PRODUCT"));
    }
    @Test void detectsOrderCreatedWithoutConfirmedDraft() {
        OrderConfirmationMysqlIT.orders.create(1L,"bypass-test");
        assertTrue(BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc).contains("ORDER_WITHOUT_CONFIRMATION"));
    }
}
