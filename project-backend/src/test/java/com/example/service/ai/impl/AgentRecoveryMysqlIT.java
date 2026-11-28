package com.example.service.ai.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.ai.model.response.CartToolResult;
import com.example.ai.tool.CartTool;
import com.example.ai.tool.ProductDetailTool;
import com.example.ai.tool.InventoryTool;
import com.example.ai.tool.support.CartOperationIdentity;
import com.example.common.Const;
import com.example.controller.AiController;
import com.example.controller.CartController;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AgentRunResponse;
import com.example.exception.*;
import com.example.mapper.*;
import com.example.service.cart.CartService;
import com.example.service.cart.impl.CartServiceImpl;
import com.example.service.product.impl.ProductServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Flyway V1-V11 + MyBatis + 事务代理 CartService + MVC 控制器；不访问现有商城数据或模型。 */
@EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_MYSQL_URL", matches = ".+")
class AgentRecoveryMysqlIT {
    private static final String SCHEMA = "shopping_agent_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String CLIENT = "recovery-001";
    private static final String ADD = "{\"request\":{\"operation\":\"add\",\"productId\":1,\"quantity\":1}}";
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static CartTool cartTool;
    private static CartService cartService;
    private static FrozenToolExecutor executor;
    private static boolean created;

    @BeforeAll
    static void setup() throws Exception {
        String server = System.getenv("SHOPPING_TEST_MYSQL_URL");
        if (!server.matches("jdbc:mysql://[A-Za-z0-9.:-]+/")) throw new IllegalArgumentException("测试 URL 必须为服务器根地址");
        admin = new JdbcTemplate(source(server));
        admin.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        var source = source(server + SCHEMA);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertEquals(11, migration.migrationsExecuted);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(CartMapper.class, CartItemMapper.class, ProductMapper.class, CartOperationRecordMapper.class)) configuration.addMapper(mapper);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var product = new ProductServiceImpl();
        ReflectionTestUtils.setField(product, "productMapper", session.getMapper(ProductMapper.class));
        var cart = new CartServiceImpl();
        ReflectionTestUtils.setField(cart, "cartMapper", session.getMapper(CartMapper.class));
        ReflectionTestUtils.setField(cart, "cartItemMapper", session.getMapper(CartItemMapper.class));
        ReflectionTestUtils.setField(cart, "productService", product);
        var proxy = new ProxyFactory(cart);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        cartService = (CartService) proxy.getProxy();
        var idempotency = new CartOperationIdempotencyServiceImpl();
        ReflectionTestUtils.setField(idempotency, "recordMapper", session.getMapper(CartOperationRecordMapper.class));
        ReflectionTestUtils.setField(idempotency, "transactionManager", manager);
        cartTool = new CartTool();
        ReflectionTestUtils.setField(cartTool, "cartService", cartService);
        ReflectionTestUtils.setField(cartTool, "operationIdempotencyService", idempotency);
        var detail = new ProductDetailTool();
        ReflectionTestUtils.setField(detail, "productService", product);
        var inventory = new InventoryTool();
        ReflectionTestUtils.setField(inventory, "productService", product);
        executor = new FrozenToolExecutor();
        ReflectionTestUtils.setField(executor, "cartTool", cartTool);
        ReflectionTestUtils.setField(executor, "productDetailTool", detail);
        ReflectionTestUtils.setField(executor, "inventoryTool", inventory);
        System.out.println("RECOVERY_IT actualCartService=true migrations=11 mysql=" + jdbc.queryForObject("SELECT VERSION()", String.class));
    }

    private static DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url + "?connectTimeout=3000&socketTimeout=15000&serverTimezone=UTC",
                System.getenv("SHOPPING_TEST_MYSQL_USER"), System.getenv("SHOPPING_TEST_MYSQL_PASSWORD"));
    }

    @BeforeEach
    void reset() {
        for (String table : List.of("agent_step_recovery_attempts", "agent_run_steps", "agent_runs", "cart_operation_records", "cart_items", "carts", "products")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO products(id,brand,model,title,price,stock,status,created_at,updated_at,deleted) VALUES (1,'test','M1','测试手机',100,10,1,NOW(),NOW(),0)");
    }

    @AfterAll
    static void cleanup() {
        if (created && SCHEMA.matches("shopping_agent_test_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE `" + SCHEMA + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, SCHEMA));
            System.out.println("MYSQL_IT temporarySchemaRemoved=true");
        }
    }

    @Test
    void commitThenCrashBeforeAuditIsReconciledWithoutAnotherWrite() {
        String run = pending("CartTool", ADD);
        assertTrue(cartTool.execute(1L, CartOperationIdentity.fromArguments(CLIENT, ADD)).success());
        runs().interrupt(1L, run, "SIMULATED_CRASH_AFTER_COMMIT");
        var step = runs().find(1L, CLIENT).steps().getFirst();
        assertEquals("SUCCEEDED", step.status());
        assertEquals("BUSINESS_RECORD", step.resultSource());
        assertEquals(0, step.retryCount());
        assertEquals(1, quantity());
        assertEquals(1, records());
        assertEquals(10, stock());
    }

    @Test
    void missingBusinessRecordDoesNotInventFailureOrWriteOnGet() {
        interrupted("CartTool", ADD);
        assertEquals("UNKNOWN", runs().find(1L, CLIENT).steps().getFirst().status());
        assertEquals(0, quantity());
        assertEquals(0, records());
    }

    @Test
    void fingerprintMismatchDoesNotReuseOtherQuantityOrAllowRecovery() {
        interrupted("CartTool", ADD);
        cartTool.execute(1L, CartOperationIdentity.request(CLIENT, "add", 1L, 2));
        var step = runs().find(1L, CLIENT).steps().getFirst();
        assertEquals("PARAMETER_CONFLICT", step.outcome());
        assertFalse(step.canRetry());
        assertThrows(ConflictException.class, () -> recovery(executor).recover(1L, CLIENT, 1, true));
        assertEquals(2, quantity());
    }

    @Test
    void originalWriteNeedsConfirmationAndUsesOriginalIdentity() {
        interrupted("CartTool", ADD);
        assertThrows(ConflictException.class, () -> recovery(executor).recover(1L, CLIENT, 1, false));
        assertEquals(0, runs().find(1L, CLIENT).steps().getFirst().retryCount());
        var result = recovery(executor).recover(1L, CLIENT, 1, true);
        assertEquals("SUCCEEDED", result.steps().getFirst().status());
        assertEquals(1, result.steps().getFirst().recoveryAttempts().size());
        recovery(executor).recover(1L, CLIENT, 1, true);
        assertEquals(1, quantity());
        assertEquals(1, records());
    }

    @Test
    void concurrentRecoveryClaimsOneExecution() throws Exception {
        interrupted("CartTool", ADD);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var slow = new FrozenToolExecutor() {
            public Object execute(Long user, String client, String tool, String args, List<Object> facts) {
                calls.incrementAndGet();
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return executor.execute(user, client, tool, args, facts);
            }
        };
        try (var pool = Executors.newFixedThreadPool(8)) {
            var owner = pool.submit(() -> recovery(slow).recover(1L, CLIENT, 1, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            List<Future<?>> duplicates = new ArrayList<>();
            for (int i = 0; i < 7; i++) duplicates.add(pool.submit(() -> assertThrows(ConflictException.class,
                    () -> recovery(slow).recover(1L, CLIENT, 1, true))));
            try { for (var duplicate : duplicates) duplicate.get(8, TimeUnit.SECONDS); }
            finally { release.countDown(); }
            assertEquals("SUCCEEDED", owner.get(10, TimeUnit.SECONDS).steps().getFirst().status());
        }
        assertEquals(1, calls.get());
        assertEquals(1, quantity());
        assertEquals(1, runs().find(1L, CLIENT).steps().getFirst().retryCount());
    }

    @Test
    void recoveryResponseLostAfterCommitIsResolvedFromBusinessRecord() {
        interrupted("CartTool", ADD);
        var lostResponse = new FrozenToolExecutor() {
            public Object execute(Long user, String client, String tool, String args, List<Object> facts) {
                executor.execute(user, client, tool, args, facts);
                throw new IllegalStateException("模拟业务成功后连接断开");
            }
        };
        var result = recovery(lostResponse).recover(1L, CLIENT, 1, true);
        assertEquals("BUSINESS_RECORD", result.steps().getFirst().resultSource());
        assertEquals("SUCCEEDED", result.steps().getFirst().status());
        assertEquals("UNKNOWN", result.steps().getFirst().recoveryAttempts().getFirst().status());
        recovery(executor).recover(1L, CLIENT, 1, true);
        assertEquals(1, quantity());
    }

    @Test
    void transientReadFailuresHaveDurableTwoAttemptLimit() {
        interrupted("ProductDetailTool", "{\"productId\":1}");
        AtomicInteger calls = new AtomicInteger();
        var broken = new FrozenToolExecutor() {
            public Object execute(Long user, String client, String tool, String args, List<Object> facts) {
                calls.incrementAndGet();
                throw new IllegalStateException("模拟查询连接失败");
            }
        };
        recovery(broken).recover(1L, CLIENT, 1, false);
        recovery(broken).recover(1L, CLIENT, 1, false);
        assertThrows(ConflictException.class, () -> recovery(broken).recover(1L, CLIENT, 1, false));
        assertEquals(2, calls.get());
        assertEquals(2, runs().find(1L, CLIENT).steps().getFirst().recoveryAttempts().size());
        assertFalse(runs().find(1L, CLIENT).steps().getFirst().canRetry());
    }

    @Test
    void stockFailureRollsBackRealCartAndStopsRetries() {
        interrupted("CartTool", ADD);
        jdbc.update("UPDATE products SET stock=0 WHERE id=1");
        var step = recovery(executor).recover(1L, CLIENT, 1, true).steps().getFirst();
        assertEquals("FAILED", step.status());
        assertEquals("BUSINESS_FAILURE", step.outcome());
        assertFalse(step.canRetry());
        assertEquals(0, quantity());
        assertEquals(0, records());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM carts", Integer.class));
    }

    @Test
    void productOffSaleIsRecheckedAtRecoveryTime() {
        interrupted("CartTool", ADD);
        jdbc.update("UPDATE products SET status=0 WHERE id=1");
        assertEquals("FAILED", recovery(executor).recover(1L, CLIENT, 1, true).steps().getFirst().status());
        assertEquals(0, quantity());
        assertEquals(0, records());
    }

    @Test
    void successfulReadRecoveryUsesSavedProductAndDoesNotWriteCart() {
        interrupted("ProductDetailTool", "{\"productId\":1}");
        var step = recovery(executor).recover(1L, CLIENT, 1, false).steps().getFirst();
        assertEquals("SUCCEEDED", step.status());
        assertTrue(step.resultJson().contains("测试手机"));
        assertEquals(0, records());
        assertEquals(0, quantity());
    }

    @Test
    void activeTaskCannotBeRecovered() {
        pending("CartTool", ADD);
        assertThrows(ConflictException.class, () -> recovery(executor).recover(1L, CLIENT, 1, true));
        assertEquals(0, quantity());
    }

    @Test
    void unknownEarlierStepBlocksLaterRecovery() {
        String run = pending("ProductDetailTool", "{\"productId\":1}");
        runs().startStep(1L, run, 2, "CartTool", ADD);
        runs().interrupt(1L, run, "CLIENT_DISCONNECTED");
        assertThrows(ConflictException.class, () -> recovery(executor).recover(1L, CLIENT, 2, true));
        recovery(executor).recover(1L, CLIENT, 1, false);
        assertEquals("SUCCEEDED", recovery(executor).recover(1L, CLIENT, 2, true).steps().get(1).status());
        assertEquals(1, quantity());
    }

    @Test
    void orderPreviewAndUnknownToolsCannotBeReexecuted() {
        interrupted("OrderPreviewTool", "{}");
        assertThrows(ConflictException.class, () -> recovery(executor).recover(1L, CLIENT, 1, true));
        assertEquals(0, runs().find(1L, CLIENT).steps().getFirst().retryCount());
    }

    @Test
    void staleRecoveryLeaseCanResumeButLateFailureCannotOverwriteSuccess() {
        String run = interrupted("CartTool", ADD);
        jdbc.update("UPDATE agent_run_steps SET retry_count=1,recovery_started_at=DATE_SUB(NOW(),INTERVAL 3 MINUTE) WHERE run_id=?", run);
        jdbc.update("INSERT INTO agent_step_recovery_attempts(run_id,sequence_no,attempt_no,status,created_at) VALUES (?,1,1,'RUNNING',DATE_SUB(NOW(),INTERVAL 3 MINUTE))", run);
        var step = recovery(executor).recover(1L, CLIENT, 1, true).steps().getFirst();
        assertEquals("SUCCEEDED", step.status());
        assertEquals(2, step.retryCount());
        assertEquals("UNKNOWN", step.recoveryAttempts().getFirst().status());
        runs().finishStep(1L, run, 1, "SYSTEM_FAILURE", null);
        assertEquals("SUCCEEDED", runs().find(1L, CLIENT).steps().getFirst().status());
        assertEquals(1, quantity());
    }

    @Test
    void otherUsersBusinessRecordIsNeverUsed() {
        interrupted("CartTool", ADD);
        cartTool.execute(2L, CartOperationIdentity.fromArguments(CLIENT, ADD));
        assertEquals("UNKNOWN", runs().find(1L, CLIENT).steps().getFirst().status());
        assertThrows(BusinessException.class, () -> recovery(executor).recover(2L, CLIENT, 1, true));
        assertEquals(0, quantity());
    }

    @Test
    void mvcRequiresLoginOwnershipAndConfirmation() throws Exception {
        interrupted("CartTool", ADD);
        var mvc = mvc();
        mvc.perform(post(recoveryUrl()).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isUnauthorized());
        mvc.perform(post(recoveryUrl()).requestAttr(Const.ATTR_USER_ID, 2L).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isBadRequest());
        mvc.perform(post(recoveryUrl()).requestAttr(Const.ATTR_USER_ID, 1L).contentType("application/json").content("{\"confirmed\":false}")).andExpect(status().isConflict());
        assertEquals(0, quantity());
    }

    @Test
    void mvcRecoveryThenCartQueryShowsExactlyOneItemAndOriginalArguments() throws Exception {
        interrupted("CartTool", ADD);
        var mvc = mvc();
        mvc.perform(post(recoveryUrl()).requestAttr(Const.ATTR_USER_ID, 1L).contentType("application/json")
                        .content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps[0].status").value("SUCCEEDED"));
        mvc.perform(post(recoveryUrl()).requestAttr(Const.ATTR_USER_ID, 1L).contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cart").requestAttr(Const.ATTR_USER_ID, 1L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalQuantity").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value(1));
        assertEquals(1, records());
        assertEquals(10, stock());
    }

    @Test
    void malformedSavedParametersCannotBeExecuted() {
        interrupted("CartTool", "{\"request\":{\"operation\":\"add\",\"productId\":1,\"quantity\":9999}}");
        assertThrows(BusinessException.class, () -> recovery(executor).recover(1L, CLIENT, 1, true));
        assertFalse(runs().find(1L, CLIENT).steps().getFirst().canRetry());
        assertEquals(0, quantity());
    }

    @Test
    void inventoryRecoveryPreservesKnownProductBoundary() {
        interrupted("InventoryTool", "{\"request\":{\"productIds\":[1]}}");
        var result = recovery(executor).recover(1L, CLIENT, 1, false);
        assertEquals("FAILED", result.steps().getFirst().status());
        assertEquals("BUSINESS_FAILURE", result.steps().getFirst().outcome());
    }

    @Test
    void corruptedBusinessResultIsNotReportedAsSuccess() {
        interrupted("CartTool", ADD);
        cartTool.execute(1L, CartOperationIdentity.fromArguments(CLIENT, ADD));
        jdbc.update("UPDATE cart_operation_records SET result_json='{}'");
        assertEquals("UNKNOWN", runs().find(1L, CLIENT).steps().getFirst().status());
        assertEquals(1, quantity());
    }

    @Test
    void transientOrderQueryFailureRemainsRecoverableWithoutCrossingUserBoundary() {
        interrupted("OrderQueryTool", "{\"request\":{\"operation\":\"list\"}}");
        AtomicInteger calls = new AtomicInteger();
        var service = (com.example.service.order.OrderService) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.example.service.order.OrderService.class}, (proxy, method, args) -> {
                    assertEquals("page", method.getName());
                    assertEquals(1L, args[0]);
                    if (calls.incrementAndGet() == 1) throw new IllegalStateException("模拟订单查询连接失败");
                    return new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 0);
                });
        var query = new com.example.ai.tool.OrderQueryTool();
        ReflectionTestUtils.setField(query, "orderService", service);
        var reader = new FrozenToolExecutor();
        ReflectionTestUtils.setField(reader, "orderQueryTool", query);
        var first = recovery(reader).recover(1L, CLIENT, 1, false).steps().getFirst();
        assertEquals("SYSTEM_FAILURE", first.outcome());
        assertTrue(first.canRetry());
        assertEquals("SUCCEEDED", recovery(reader).recover(1L, CLIENT, 1, false).steps().getFirst().status());
        assertEquals(2, calls.get());
    }

    private JdbcAgentRunService runs() { return new JdbcAgentRunService(jdbc, manager); }
    private AgentRecoveryService recovery(FrozenToolExecutor toolExecutor) { return new AgentRecoveryService(jdbc, manager, runs(), toolExecutor); }
    private String pending(String tool, String args) {
        var request = new AiChatRequest();
        request.setClientRequestId(CLIENT);
        request.setConversationId("conversation-001");
        request.setMessage("添加一台测试手机");
        String run = runs().begin(1L, request).runId();
        runs().startStep(1L, run, 1, tool, args);
        return run;
    }
    String interrupted(String tool, String args) {
        String run = pending(tool, args);
        runs().interrupt(1L, run, "CLIENT_DISCONNECTED");
        return run;
    }
    private int quantity() { return jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity),0) FROM cart_items i JOIN carts c ON c.id=i.cart_id WHERE c.user_id=1 AND i.deleted=0", Integer.class); }
    private int records() { return jdbc.queryForObject("SELECT COUNT(*) FROM cart_operation_records", Integer.class); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM products WHERE id=1", Integer.class); }
    private String recoveryUrl() { return "/api/ai/runs/" + CLIENT + "/steps/1/recover"; }
    void assertRecoveredOnce() { assertEquals(1, quantity()); assertEquals(1, records()); assertEquals(10, stock()); }
    MockMvc mvc() {
        var ai = new AiController();
        ReflectionTestUtils.setField(ai, "agentRunService", runs());
        ReflectionTestUtils.setField(ai, "agentRecoveryService", recovery(executor));
        var cart = new CartController();
        ReflectionTestUtils.setField(cart, "cartService", cartService);
        return MockMvcBuilders.standaloneSetup(ai, cart).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
