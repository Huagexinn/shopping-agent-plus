package com.example.service.ai.impl;

import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.AiChatResponse;
import com.example.exception.BusinessException;
import com.example.exception.ConflictException;
import com.example.ai.memory.ConversationMemoryService;
import com.example.ai.memory.ConversationMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 MySQL 的任务状态机与驱动集成；模型用确定性替身，不冒充真实模型评测。 */
@EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_MYSQL_URL", matches = ".+")
class AgentRunMysqlIT {
    private static final String SCHEMA = "shopping_agent_test_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private static boolean created;

    @BeforeAll
    static void setup() {
        String server = System.getenv("SHOPPING_TEST_MYSQL_URL");
        if (!server.matches("jdbc:mysql://[A-Za-z0-9.:-]+/")) throw new IllegalArgumentException("测试 URL 必须为服务器根地址");
        admin = new JdbcTemplate(source(server));
        System.out.println("MYSQL_IT serverVersion=" + admin.queryForObject("SELECT VERSION()", String.class));
        admin.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        var source = source(server + SCHEMA);
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__create_cart_operation_records.sql"),
                new ClassPathResource("db/migration/V9__create_agent_runs.sql"),
                new ClassPathResource("db/migration/V10__create_agent_step_recovery.sql")).execute(source);
    }

    private static DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url + "?connectTimeout=3000&socketTimeout=15000&serverTimezone=UTC",
                System.getenv("SHOPPING_TEST_MYSQL_USER"), System.getenv("SHOPPING_TEST_MYSQL_PASSWORD"));
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM agent_step_recovery_attempts");
        jdbc.update("DELETE FROM cart_operation_records");
        jdbc.update("DELETE FROM agent_run_steps");
        jdbc.update("DELETE FROM agent_runs");
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
    void concurrentSubmissionsHaveOneOwner() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try { service().begin(1L, request()); accepted.incrementAndGet(); }
                catch (ConflictException e) { rejected.incrementAndGet(); }
                return null;
            }));
            start.countDown();
            for (var result : results) result.get(20, TimeUnit.SECONDS);
        }
        assertEquals(1, accepted.get());
        assertEquals(7, rejected.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Integer.class));
    }

    @Test
    void payloadAndConversationAreBoundToIdentity() {
        service().begin(1L, request());
        var changed = request();
        changed.setMessage("另买两台");
        assertThrows(ConflictException.class, () -> service().begin(1L, changed));
        changed.setMessage(request().getMessage());
        changed.setConversationId("conversation-002");
        assertThrows(ConflictException.class, () -> service().begin(1L, changed));
    }

    @Test
    void usersCannotReadOrMutateOtherUsersRuns() {
        String id = service().begin(1L, request()).runId();
        assertThrows(BusinessException.class, () -> service().find(2L, request().getClientRequestId()));
        assertTrue(service().recent(2L, request().getConversationId()).isEmpty());
        assertThrows(ConflictException.class, () -> service().startStep(2L, id, 1, "CartTool", "{}"));
        service().interrupt(2L, id, "OTHER_USER");
        assertEquals("RUNNING", service().find(1L, request().getClientRequestId()).status());
        assertNotEquals(id, service().begin(2L, request()).runId());
    }

    @Test
    void identitiesAreCaseSensitive() {
        var upper = request();
        upper.setClientRequestId("Request-001");
        assertNotEquals(service().begin(1L, request()).runId(), service().begin(1L, upper).runId());
    }

    @Test
    void savedResponseSurvivesNewInstanceAndBothDriversSkipModel() {
        var first = service().begin(1L, request());
        var response = response();
        service().finish(1L, first.runId(), response);
        assertEquals(response, service().begin(1L, request()).replay());
        var sync = new AiChatServiceImpl();
        var stream = new AiChatStreamDriver();
        var core = core();
        var forbiddenGateway = new AiModelGateway() {
            public ChatClientResponse call(ChatClient.ChatClientRequestSpec spec) { fail("不得重复调用模型"); return null; }
            public Flux<ChatClientResponse> stream(ChatClient.ChatClientRequestSpec spec) { fail("不得重复调用模型"); return Flux.empty(); }
        };
        ReflectionTestUtils.setField(sync, "core", core);
        ReflectionTestUtils.setField(sync, "modelGateway", forbiddenGateway);
        ReflectionTestUtils.setField(stream, "core", core);
        ReflectionTestUtils.setField(stream, "modelGateway", forbiddenGateway);
        assertEquals(response, sync.chat(1L, request()));
        var events = stream.stream(1L, request()).collectList().block();
        assertNotNull(events);
        assertEquals(List.of("meta", "final"), events.stream().map(e -> e.event()).toList());
        assertEquals(response, events.getLast().payload());
    }

    @Test
    void interruptedRunIsNotReexecutedAndNewToolsAreFenced() {
        var run = service().begin(1L, request());
        service().startStep(1L, run.runId(), 1, "CartTool", "{}");
        service().interrupt(1L, run.runId(), "CLIENT_DISCONNECTED");
        assertThrows(ConflictException.class, () -> service().begin(1L, request()));
        assertThrows(ConflictException.class, () -> service().startStep(1L, run.runId(), 2, "CartTool", "{}"));
        assertThrows(ConflictException.class, () -> service().finish(1L, run.runId(), response()));
        assertEquals("UNKNOWN", service().find(1L, request().getClientRequestId()).steps().getFirst().status());
    }

    @Test
    void lateCommittedToolResultIsRetainedAfterInterruption() {
        var run = service().begin(1L, request());
        service().startStep(1L, run.runId(), 1, "CartTool", "{}");
        service().interrupt(1L, run.runId(), "CLIENT_DISCONNECTED");
        service().finishStep(1L, run.runId(), 1, "SUCCESS", Map.of("quantity", 1));
        var view = service().find(1L, request().getClientRequestId());
        assertEquals("INTERRUPTED", view.status());
        assertEquals("SUCCEEDED", view.steps().getFirst().status());
        assertTrue(view.steps().getFirst().resultJson().contains("quantity"));
    }

    @Test
    void auditPersistsIntentBeforeExecutionAndUnknownOnFailure() {
        var run = service().begin(1L, request());
        var audit = new NativeToolAudit(run.runId(), "http-request", "conversation-001", "user", 1L, service());
        var entry = audit.start("CartTool", "{\"request\":{\"operation\":\"get\"}}");
        assertEquals("RUNNING", service().find(1L, request().getClientRequestId()).steps().getFirst().status());
        audit.fail(entry, new IllegalStateException("连接断开"));
        assertEquals("UNKNOWN", service().find(1L, request().getClientRequestId()).steps().getFirst().status());
        assertFalse(audit.responses().getFirst().resultSummary().contains("未产生副作用"));
    }

    @Test
    void expiredHeartbeatIsInterruptedWithoutAutomaticTakeover() {
        var run = service().begin(1L, request());
        jdbc.update("UPDATE agent_runs SET heartbeat_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 6 MINUTE) WHERE run_id=?", run.runId());
        assertEquals("INTERRUPTED", service().find(1L, request().getClientRequestId()).status());
        assertThrows(ConflictException.class, () -> service().begin(1L, request()));
        service().heartbeat(1L, run.runId());
        assertEquals("INTERRUPTED", service().find(1L, request().getClientRequestId()).status());
    }

    @Test
    void heartbeatKeepsLiveRunQueryable() {
        var run = service().begin(1L, request());
        jdbc.update("UPDATE agent_runs SET heartbeat_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 6 MINUTE) WHERE run_id=?", run.runId());
        service().heartbeat(1L, run.runId());
        assertEquals("RUNNING", service().find(1L, request().getClientRequestId()).status());
    }

    @Test
    void streamCancellationPersistsInterruption() {
        var stream = new AiChatStreamDriver();
        ReflectionTestUtils.setField(stream, "core", core());
        ReflectionTestUtils.setField(stream, "modelGateway", new AiModelGateway() {
            public Flux<ChatClientResponse> stream(ChatClient.ChatClientRequestSpec spec) { return Flux.never(); }
        });
        var subscription = stream.stream(1L, request()).subscribe();
        subscription.dispose();
        assertEquals("INTERRUPTED", service().find(1L, request().getClientRequestId()).status());
    }

    @Test
    void failedModelResponseIsPersistedAndReplayedWithoutSecondCall() {
        var sync = new AiChatServiceImpl();
        ReflectionTestUtils.setField(sync, "core", core());
        AtomicInteger calls = new AtomicInteger();
        ReflectionTestUtils.setField(sync, "modelGateway", new AiModelGateway() {
            public ChatClientResponse call(ChatClient.ChatClientRequestSpec spec) {
                calls.incrementAndGet();
                throw new IllegalStateException("模拟模型不可用");
            }
        });
        var first = sync.chat(1L, request());
        assertEquals(first, sync.chat(1L, request()));
        assertEquals(1, calls.get());
        assertEquals("FAILED", service().find(1L, request().getClientRequestId()).status());
    }

    @Test
    void streamCompletionStoresFailureBeforeEmittingTerminalEvent() {
        var stream = new AiChatStreamDriver();
        ReflectionTestUtils.setField(stream, "core", core());
        ReflectionTestUtils.setField(stream, "modelGateway", new AiModelGateway() {
            public Flux<ChatClientResponse> stream(ChatClient.ChatClientRequestSpec spec) { return Flux.empty(); }
        });
        var events = stream.stream(1L, request()).doOnNext(event -> {
            if (event.event().equals("error")) {
                assertNotNull(service().find(1L, request().getClientRequestId()).response());
            }
        }).collectList().block(java.time.Duration.ofSeconds(3));
        assertNotNull(events);
        assertEquals(List.of("meta", "error"), events.stream().map(e -> e.event()).toList());
        assertEquals("FAILED", service().find(1L, request().getClientRequestId()).status());
    }

    @Test
    void streamPersistenceFailureTerminatesInsteadOfHangingOrClaimingSuccess() {
        var core = core();
        ReflectionTestUtils.setField(core, "agentRunService", new JdbcAgentRunService(jdbc, manager) {
            public void finish(Long user, String run, AiChatResponse response) {
                throw new IllegalStateException("模拟结果落库失败");
            }
        });
        var stream = new AiChatStreamDriver();
        ReflectionTestUtils.setField(stream, "core", core);
        ReflectionTestUtils.setField(stream, "modelGateway", new AiModelGateway() {
            public Flux<ChatClientResponse> stream(ChatClient.ChatClientRequestSpec spec) { return Flux.empty(); }
        });
        var exception = assertThrows(IllegalStateException.class,
                () -> stream.stream(1L, request()).collectList().block(java.time.Duration.ofSeconds(3)));
        assertEquals("模拟结果落库失败", exception.getMessage());
        var run = service().find(1L, request().getClientRequestId());
        assertEquals("INTERRUPTED", run.status());
        assertNull(run.response());
    }

    private JdbcAgentRunService service() { return new JdbcAgentRunService(jdbc, manager); }

    private AiOrchestrationCore core() {
        var core = new AiOrchestrationCore() {
            public ChatClient.ChatClientRequestSpec buildSpec(Prepared prepared) { return null; }
        };
        ReflectionTestUtils.setField(core, "agentRunService", service());
        ReflectionTestUtils.setField(core, "conversationMemoryService", new ConversationMemoryService() {
            public List<ConversationMessage> getHistory(Long user, String conversation) { return List.of(); }
            public void append(Long user, String conversation, List<ConversationMessage> messages) {}
        });
        return core;
    }

    private static AiChatRequest request() {
        var request = new AiChatRequest();
        request.setClientRequestId("request-001");
        request.setConversationId("conversation-001");
        request.setMessage("你好");
        return request;
    }

    private static AiChatResponse response() { return new AiChatResponse("conversation-001", "你好，请告诉我购机需求", List.of(), List.of()); }
}
