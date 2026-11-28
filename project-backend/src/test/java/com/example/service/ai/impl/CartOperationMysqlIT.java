package com.example.service.ai.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.ai.model.response.CartToolResult;
import com.example.dto.cart.Response.CartResponse;
import com.example.exception.ConflictException;
import com.example.mapper.CartOperationRecordMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式启用的 MySQL 集成验证，不启动 Spring Boot、Redis、Milvus 或模型。
 * URL 必须是服务器根地址，例如 jdbc:mysql://127.0.0.1:3306/。
 * 创建随机独立数据库，结束后只删除本测试创建的库，绝不读写商城业务表。
 */
@EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_MYSQL_URL", matches = ".+")
class CartOperationMysqlIT {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String SCHEMA = "shopping_agent_test_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static CartOperationRecordMapper mapper;
    private static DataSourceTransactionManager transactions;
    private static boolean created;

    @BeforeAll
    static void createIsolatedDatabase() throws Exception {
        String server = System.getenv("SHOPPING_TEST_MYSQL_URL");
        if (!server.matches("jdbc:mysql://[A-Za-z0-9.:-]+/")) {
            throw new IllegalArgumentException("测试 URL 必须为 MySQL 服务器根地址，不含库名或查询参数");
        }
        admin = new JdbcTemplate(dataSource(server));
        System.out.println("MYSQL_IT serverVersion=" + admin.queryForObject("SELECT VERSION()", String.class));
        admin.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        var dataSource = dataSource(server + SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V8__create_cart_operation_records.sql")).execute(dataSource);
        jdbc.execute("CREATE TABLE test_cart_counter (user_id BIGINT NOT NULL PRIMARY KEY, quantity INT NOT NULL) ENGINE=InnoDB");
        transactions = new DataSourceTransactionManager(dataSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(CartOperationRecordMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(CartOperationRecordMapper.class);
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url + "?connectTimeout=3000&socketTimeout=15000&serverTimezone=UTC",
                System.getenv("SHOPPING_TEST_MYSQL_USER"), System.getenv("SHOPPING_TEST_MYSQL_PASSWORD"));
    }

    @BeforeEach
    void resetFixture() {
        jdbc.update("DELETE FROM cart_operation_records");
        jdbc.update("DELETE FROM test_cart_counter");
    }

    @AfterAll
    static void dropOnlyCreatedDatabase() {
        if (created && SCHEMA.matches("shopping_agent_test_[a-f0-9]{32}")) {
            admin.execute("DROP DATABASE `" + SCHEMA + "`");
            assertEquals(0, admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", Integer.class, SCHEMA));
            System.out.println("MYSQL_IT temporarySchemaRemoved=true");
        }
    }

    @Test
    void lostResponseAndNewServiceInstanceReuseCommittedResultWithoutRedis() {
        service().execute(1L, "operation-001", FINGERPRINT, () -> addOne(1L));
        var replay = service().execute(1L, "operation-001", FINGERPRINT, () -> addOne(1L));
        assertTrue(replay.success());
        assertEquals(1, replay.cart().totalQuantity());
        assertEquals(1, quantity(1L));
        assertEquals(1, recordCount());
    }

    @Test
    void concurrentDuplicatesCommitExactlyOneWrite() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CartToolResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service().execute(1L, "operation-001", FINGERPRINT, () -> addOne(1L));
            }));
            start.countDown();
            for (Future<CartToolResult> result : futures) assertTrue(result.get(20, TimeUnit.SECONDS).success());
        }
        assertEquals(1, quantity(1L));
        assertEquals(1, recordCount());
    }

    @Test
    void changedFingerprintDoesNotWriteAgain() {
        service().execute(1L, "operation-001", FINGERPRINT, () -> addOne(1L));
        assertThrows(ConflictException.class, () -> service().execute(
                1L, "operation-001", "b".repeat(64), () -> addOne(1L)));
        assertEquals(1, quantity(1L));
    }

    @Test
    void businessFailureRollsBackBothOperationAndCartThenAllowsRetry() {
        var failed = service().execute(1L, "operation-001", FINGERPRINT, () -> {
            addOne(1L);
            return new CartToolResult("CartTool", false, "add", "模拟业务失败", null, 1L);
        });
        assertFalse(failed.success());
        assertEquals(0, quantity(1L));
        assertEquals(0, recordCount());
        assertTrue(service().execute(1L, "operation-001", FINGERPRINT, () -> addOne(1L)).success());
        assertEquals(1, quantity(1L));
    }

    @Test
    void systemFailureRollsBackBothOperationAndCart() {
        assertThrows(IllegalStateException.class, () -> service().execute(1L, "operation-001", FINGERPRINT, () -> {
            addOne(1L);
            throw new IllegalStateException("模拟提交前失败");
        }));
        assertEquals(0, quantity(1L));
        assertEquals(0, recordCount());
    }

    @Test
    void operationKeysAreUserScopedAndCaseSensitive() {
        service().execute(1L, "request-A", FINGERPRINT, () -> addOne(1L));
        service().execute(1L, "request-a", FINGERPRINT, () -> addOne(1L));
        service().execute(2L, "request-A", FINGERPRINT, () -> addOne(2L));
        assertEquals(2, quantity(1L));
        assertEquals(1, quantity(2L));
        assertEquals(3, recordCount());
    }

    private CartOperationIdempotencyServiceImpl service() {
        var service = new CartOperationIdempotencyServiceImpl();
        ReflectionTestUtils.setField(service, "recordMapper", mapper);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        // 不注入 Redis，明确验证数据库是最终依据，缓存缺失不影响幂等。
        return service;
    }

    private CartToolResult addOne(Long userId) {
        jdbc.update("INSERT INTO test_cart_counter(user_id, quantity) VALUES (?, 1) ON DUPLICATE KEY UPDATE quantity = quantity + 1", userId);
        return new CartToolResult("CartTool", true, "add", "已添加", new CartResponse(
                userId, List.of(), quantity(userId), BigDecimal.ZERO), 1L);
    }

    private int quantity(Long userId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM test_cart_counter WHERE user_id = ?", Integer.class, userId);
    }

    private int recordCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_operation_records", Integer.class);
    }
}
