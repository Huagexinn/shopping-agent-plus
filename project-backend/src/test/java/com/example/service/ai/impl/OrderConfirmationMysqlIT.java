package com.example.service.ai.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import com.example.common.Const;
import com.example.controller.OrderController;
import com.example.dto.order.Response.*;
import com.example.exception.*;
import com.example.mapper.*;
import com.example.service.order.*;
import com.example.service.order.impl.*;
import com.example.service.product.impl.ProductServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** V1-V11 + 真实 MyBatis/事务/MVC。无 Redis、无模型、无支付平台；仅使用独立临时库。 */
@EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_MYSQL_URL", matches = ".+")
class OrderConfirmationMysqlIT {
    private static final String SCHEMA = "shopping_agent_test_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate jdbc;
    private static JdbcTemplate admin;
    private static boolean created;
    static DataSourceTransactionManager manager;
    private static SqlSessionTemplate session;
    static OrderDraftService drafts;
    static OrderService orders;
    private static OrderDraftStore store;

    @BeforeAll static void setup() throws Exception {
        String server = System.getenv("SHOPPING_TEST_MYSQL_URL");
        if (!server.matches("jdbc:mysql://[A-Za-z0-9.:-]+/")) throw new IllegalArgumentException("只允许测试服务器根 URL");
        admin = new JdbcTemplate(source(server));
        admin.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        var source = source(server + SCHEMA);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        assertEquals(11, Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate().migrationsExecuted);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(CartMapper.class, CartItemMapper.class, ProductMapper.class, OrderMapper.class, OrderItemMapper.class, CartOperationRecordMapper.class)) config.addMapper(mapper);
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(config); factory.setPlugins(pagination);
        session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        store = new OrderDraftStore(jdbc);
        drafts = proxy(new JdbcOrderDraftService(store, session.getMapper(CartMapper.class), session.getMapper(CartItemMapper.class), session.getMapper(ProductMapper.class)), OrderDraftService.class);
        orders = orderService(session.getMapper(OrderItemMapper.class));
        System.out.println("ORDER_IT mysql=" + jdbc.queryForObject("SELECT VERSION()", String.class) + " migrations=11 redis=false");
    }
    private static DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url + "?connectTimeout=3000&socketTimeout=20000&serverTimezone=UTC", System.getenv("SHOPPING_TEST_MYSQL_USER"), System.getenv("SHOPPING_TEST_MYSQL_PASSWORD"));
    }
    private static <T> T proxy(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
    private static OrderService orderService(OrderItemMapper itemMapper) {
        var service = new OrderServiceImpl();
        ReflectionTestUtils.setField(service, "cartMapper", session.getMapper(CartMapper.class));
        ReflectionTestUtils.setField(service, "cartItemMapper", session.getMapper(CartItemMapper.class));
        ReflectionTestUtils.setField(service, "productMapper", session.getMapper(ProductMapper.class));
        ReflectionTestUtils.setField(service, "orderMapper", session.getMapper(OrderMapper.class));
        ReflectionTestUtils.setField(service, "orderItemMapper", itemMapper);
        ReflectionTestUtils.setField(service, "draftStore", store);
        ReflectionTestUtils.setField(service, "orderDraftService", drafts);
        var products = new ProductServiceImpl();
        ReflectionTestUtils.setField(products, "productMapper", session.getMapper(ProductMapper.class));
        ReflectionTestUtils.setField(service, "productService", products);
        return proxy(service, OrderService.class);
    }
    @BeforeEach void reset() {
        for (String table : List.of("order_drafts", "order_items", "orders", "cart_items", "carts", "products")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO products(id,brand,model,title,price,stock,status,created_at,updated_at,deleted) VALUES (1,'测试品牌','M1','测试手机',100,10,1,NOW(),NOW(),0),(2,'测试品牌','M2','测试耳机',50,10,1,NOW(),NOW(),0)");
        jdbc.update("INSERT INTO carts(id,user_id,created_at,updated_at,deleted) VALUES (1,1,NOW(),NOW(),0)");
        addCart(1, 2);
    }
    static void addCart(int product, int quantity) {
        jdbc.update("INSERT INTO cart_items(cart_id,product_id,quantity,display_price,created_at,updated_at,deleted) VALUES (1,?,?,100,NOW(),NOW(),0)", product, quantity);
    }
    @AfterAll static void cleanup() {
        if (created && SCHEMA.matches("shopping_agent_test_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE `" + SCHEMA + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, SCHEMA));
            System.out.println("MYSQL_IT temporarySchemaRemoved=true");
            created = false;
        }
    }
    static MockMvc mvc(Object... additionalControllers) {
        var controller = new OrderController();
        ReflectionTestUtils.setField(controller, "orderService", orders);
        ReflectionTestUtils.setField(controller, "orderDraftService", drafts);
        List<Object> controllers = new ArrayList<>(List.of(additionalControllers));
        controllers.add(controller);
        return MockMvcBuilders.standaloneSetup(controllers.toArray()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    static int scalar(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    // 工程评测复用同一个真实数据库夹具，避免另建一套订单事务替身。
    static <T> T mapper(Class<T> type) { return session.getMapper(type); }
    private OrderResponse confirm(OrderDraftResponse d) { return orders.confirmDraft(1L, d.draftId(), "confirm-" + d.draftId(), d.fingerprint()); }
    static void assertConfirmedOnce() {
        assertEquals(1, scalar("SELECT COUNT(*) FROM orders"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM order_items"));
        assertEquals(8, scalar("SELECT stock FROM products WHERE id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM cart_items WHERE deleted=0"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM order_drafts WHERE status='CONFIRMED' AND order_id IS NOT NULL"));
    }
    private void assertUnchanged() {
        assertEquals(0, scalar("SELECT COUNT(*) FROM orders"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM order_items"));
        assertEquals(10, scalar("SELECT stock FROM products WHERE id=1"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM order_drafts WHERE confirmation_key IS NOT NULL"));
    }
    @Test void previewIsPersistentReadOnlyBusinessSnapshot() {
        var draft = drafts.preview(1L);
        assertEquals(64, draft.fingerprint().length());
        assertEquals(0, draft.previewTotalAmount().compareTo(new java.math.BigDecimal("200")));
        var stored = drafts.detail(1L, draft.draftId());
        assertEquals(draft.draftId(), stored.draftId());
        assertEquals(draft.fingerprint(), stored.fingerprint());
        assertEquals(draft.items().getFirst().quantity(), stored.items().getFirst().quantity());
        assertEquals(0, draft.previewTotalAmount().compareTo(stored.previewTotalAmount()));
        assertUnchanged();
        assertEquals(2, scalar("SELECT quantity FROM cart_items WHERE deleted=0"));
    }
    @Test void confirmationAtomicallyCreatesSnapshotAndClearsCartWithoutRedis() {
        var draft = drafts.preview(1L);
        var order = confirm(draft);
        assertEquals(1, order.status());
        assertEquals(0, order.totalAmount().compareTo(draft.previewTotalAmount()));
        assertEquals(draft.items().getFirst().productTitle(), order.items().getFirst().productTitle());
        assertEquals(order.id(), drafts.detail(1L, draft.draftId()).confirmedOrderId());
        assertConfirmedOnce();
    }
    @Test void concurrentSameKeyCreatesOnlyOneOrder() throws Exception { concurrent(false); }
    @Test void concurrentDifferentKeysForSameDraftCreateOnlyOneOrder() throws Exception { concurrent(true); }
    private void concurrent(boolean differentKeys) throws Exception {
        var d = drafts.preview(1L);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Long>> results = new ArrayList<>();
            for (int i=0;i<8;i++) {
                String key = differentKeys ? "parallel-" + i : "same-key";
                results.add(pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return orders.confirmDraft(1L, d.draftId(), key, d.fingerprint()).id(); }));
            }
            start.countDown();
            Set<Long> ids = new HashSet<>();
            for (var result : results) ids.add(result.get(15, TimeUnit.SECONDS));
            assertEquals(1, ids.size()); assertConfirmedOnce();
        }
    }
    @Test void oneKeyCannotConfirmTwoDrafts() {
        var first = drafts.preview(1L); var second = drafts.preview(1L);
        orders.confirmDraft(1L, first.draftId(), "shared", first.fingerprint());
        assertThrows(ConflictException.class, () -> orders.confirmDraft(1L, second.draftId(), "shared", second.fingerprint()));
        assertConfirmedOnce();
    }
    @Test void confirmedResultSurvivesExpiryAndServiceRecreation() {
        var d = drafts.preview(1L); var order = confirm(d);
        jdbc.update("UPDATE order_drafts SET expires_at=NOW()-INTERVAL 1 MINUTE");
        assertEquals("CONFIRMED", drafts.detail(1L, d.draftId()).status());
        assertEquals(order.id(), orderService(session.getMapper(OrderItemMapper.class)).confirmDraft(1L,d.draftId(),"new-key",d.fingerprint()).id());
        assertConfirmedOnce();
    }
    @Test void expiredUnconfirmedDraftCannotCreateOrder() {
        var d = drafts.preview(1L); jdbc.update("UPDATE order_drafts SET expires_at=NOW()-INTERVAL 1 MINUTE");
        assertEquals("EXPIRED", drafts.detail(1L,d.draftId()).status());
        assertThrows(ConflictException.class, () -> confirm(d)); assertUnchanged();
    }
    @Test void differentUserCannotReadOrConfirmDraft() {
        var d = drafts.preview(1L);
        assertThrows(ResourceNotFoundException.class, () -> drafts.detail(2L,d.draftId()));
        assertThrows(ResourceNotFoundException.class, () -> orders.confirmDraft(2L,d.draftId(),"key",d.fingerprint()));
        assertUnchanged();
    }
    @Test void wrongFingerprintCannotConfirm() {
        var d = drafts.preview(1L);
        assertThrows(ConflictException.class, () -> orders.confirmDraft(1L,d.draftId(),"key","0".repeat(64))); assertUnchanged();
    }
    @Test void changedPriceRequiresNewPreview() {
        var d = drafts.preview(1L); jdbc.update("UPDATE products SET price=101 WHERE id=1");
        assertEquals("STALE",drafts.detail(1L,d.draftId()).status());
        assertThrows(BusinessException.class, () -> confirm(d)); assertUnchanged();
        var fresh = drafts.preview(1L);
        assertNotEquals(d.fingerprint(),fresh.fingerprint());
        assertEquals(0, confirm(fresh).totalAmount().compareTo(new java.math.BigDecimal("202")));
    }
    @Test void changedQuantityCannotBeSilentlyCharged() {
        var d = drafts.preview(1L); jdbc.update("UPDATE cart_items SET quantity=3 WHERE deleted=0");
        assertEquals("STALE",drafts.detail(1L,d.draftId()).status());
        assertThrows(BusinessException.class, () -> confirm(d)); assertUnchanged();
        assertEquals(3, scalar("SELECT quantity FROM cart_items WHERE deleted=0"));
    }
    @Test void addedProductCannotBeSilentlyIncluded() {
        var d = drafts.preview(1L); addCart(2,1);
        assertThrows(BusinessException.class, () -> confirm(d)); assertUnchanged();
        assertEquals(2,scalar("SELECT COUNT(*) FROM cart_items WHERE deleted=0"));
    }
    @Test void changedProductDescriptionRequiresNewConsent() {
        var d = drafts.preview(1L); jdbc.update("UPDATE products SET model='replacement' WHERE id=1");
        assertEquals("STALE",drafts.detail(1L,d.draftId()).status());
        assertThrows(ConflictException.class, () -> confirm(d)); assertUnchanged();
    }
    @Test void offSaleProductCannotBeOrdered() {
        var d = drafts.preview(1L); jdbc.update("UPDATE products SET status=0 WHERE id=1");
        assertThrows(BusinessException.class, () -> confirm(d)); assertUnchanged();
    }
    @Test void secondProductStockFailureRollsBackFirstDeduction() {
        addCart(2,2); var d = drafts.preview(1L); jdbc.update("UPDATE products SET stock=1 WHERE id=2");
        assertThrows(BusinessException.class, () -> confirm(d)); assertUnchanged();
        assertEquals(1, scalar("SELECT stock FROM products WHERE id=2"));
        assertEquals(2,scalar("SELECT COUNT(*) FROM cart_items WHERE deleted=0"));
    }
    @Test void itemInsertFailureRollsBackOrderStockCartAndBindingThenCanRetry() {
        var d = drafts.preview(1L);
        var real = session.getMapper(OrderItemMapper.class);
        var failing = (OrderItemMapper) Proxy.newProxyInstance(OrderItemMapper.class.getClassLoader(), new Class<?>[]{OrderItemMapper.class}, (proxy,method,args) -> {
            if (method.getName().equals("insert")) throw new IllegalStateException("INJECTED_ITEM_WRITE_FAILURE");
            try { return method.invoke(real,args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
        assertThrows(IllegalStateException.class, () -> orderService(failing).confirmDraft(1L,d.draftId(),"retry-key",d.fingerprint()));
        assertUnchanged();
        assertEquals(2,scalar("SELECT quantity FROM cart_items WHERE deleted=0"));
        orders.confirmDraft(1L,d.draftId(),"retry-key",d.fingerprint()); assertConfirmedOnce();
    }
    @Test void duplicateOrderKeyRollsBackStockInsteadOfReturningUnrelatedOrder() {
        var first = drafts.preview(1L); orders.confirmDraft(1L,first.draftId(),"Case-Key",first.fingerprint());
        addCart(1,2); var next = drafts.preview(1L);
        // orders 的旧键索引不区分大小写，草稿键区分；覆盖订单 insert 冲突分支。
        assertThrows(ConflictException.class, () -> orders.confirmDraft(1L,next.draftId(),"case-key",next.fingerprint()));
        assertEquals(8,scalar("SELECT stock FROM products WHERE id=1"));
        assertEquals(1,scalar("SELECT COUNT(*) FROM orders"));
        assertEquals(2,scalar("SELECT quantity FROM cart_items WHERE deleted=0"));
    }
    @Test void tamperedSnapshotIsRejected() {
        var d = drafts.preview(1L);
        jdbc.update("UPDATE order_drafts SET snapshot_json=JSON_SET(snapshot_json,'$.previewTotalAmount',1)");
        assertThrows(ConflictException.class, () -> confirm(d)); assertUnchanged();
    }
    @Test void cancelledOrderIsNotRecreatedFromSameDraft() {
        var d = drafts.preview(1L); var order = confirm(d); orders.cancel(1L,order.id());
        assertEquals(order.id(),confirm(d).id());
        assertEquals(1,scalar("SELECT COUNT(*) FROM orders"));
        assertEquals(10,scalar("SELECT stock FROM products WHERE id=1"));
    }
    @Test void concurrentPriceChangeIsObservedAfterProductLock() throws Exception {
        var d = drafts.preview(1L); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.update("UPDATE products SET price=111 WHERE id=1"); locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); } return null;
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var confirming = pool.submit(() -> confirm(d));
            release.countDown(); writer.get(10,TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> confirming.get(10,TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class,failure.getCause()); assertUnchanged();
        }
    }
    @Test void httpRequiresExplicitConfirmationAndFingerprint() throws Exception {
        var d = drafts.preview(1L); var mvc=mvc();
        for (String body : List.of("{}", "{\"confirmed\":false,\"fingerprint\":\""+d.fingerprint()+"\"}", "{\"confirmed\":true,\"fingerprint\":\"bad\"}"))
            mvc.perform(post("/api/orders/"+d.draftId()+"/confirm").requestAttr(Const.ATTR_USER_ID,1L).header("Idempotency-Key","http-key").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertUnchanged();
        mvc.perform(post("/api/orders/"+d.draftId()+"/confirm").requestAttr(Const.ATTR_USER_ID,1L).header("Idempotency-Key","http-key").contentType("application/json").content("{\"confirmed\":true,\"fingerprint\":\""+d.fingerprint()+"\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.data.totalAmount").value(200));
        assertConfirmedOnce();
    }
    @Test void httpReadIsOwnerScopedAndDoesNotCreateOrders() throws Exception {
        var d = drafts.preview(1L); var mvc=mvc();
        mvc.perform(get("/api/orders/drafts/"+d.draftId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/drafts/"+d.draftId()).requestAttr(Const.ATTR_USER_ID,2L)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/drafts/"+d.draftId()).requestAttr(Const.ATTR_USER_ID,1L)).andExpect(status().isOk()).andExpect(jsonPath("$.data.fingerprint").value(d.fingerprint()));
        assertUnchanged();
    }
    @Test void legacyDirectHttpOrderCreationRemainsDisabled() throws Exception {
        mvc().perform(post("/api/orders").requestAttr(Const.ATTR_USER_ID,1L).header("Idempotency-Key","old-key").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        assertUnchanged();
    }
}
