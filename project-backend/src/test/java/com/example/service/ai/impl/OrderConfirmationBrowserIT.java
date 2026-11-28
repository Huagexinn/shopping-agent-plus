package com.example.service.ai.impl;

import com.example.common.Const;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启动的浏览器验收夹具：真实前端 dist、订单 MVC/服务、隔离 MySQL；登录及模型回复为测试替身，首次确认响应人为丢弃。 */
@EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_MYSQL_URL", matches = ".+")
class OrderConfirmationBrowserIT {
    @Test
    void browserConfirmationAcceptance() throws Exception {
        var fixture = new OrderConfirmationMysqlIT();
        HttpServer server = null;
        try {
            OrderConfirmationMysqlIT.setup();
            fixture.reset();
            var draft = OrderConfirmationMysqlIT.drafts.preview(1L);
            var runs = new JdbcAgentRunService(OrderConfirmationMysqlIT.jdbc, OrderConfirmationMysqlIT.manager);
            var aiRequest = new com.example.dto.ai.Request.AiChatRequest();
            aiRequest.setMessage("请预览购物车，确认后再下单");
            aiRequest.setConversationId("order-browser-001"); aiRequest.setClientRequestId("order-browser-client-001");
            String runId = runs.begin(1L,aiRequest).runId();
            runs.startStep(1L,runId,1,"OrderPreviewTool","{}");
            runs.finishStep(1L,runId,1,"SUCCESS",draft);
            runs.finish(1L,runId,new com.example.dto.ai.Response.AiChatResponse(com.example.dto.ai.Response.AiChatResultStatus.SUCCESS,
                    "order-browser-001","已生成测试订单预览，请核对后明确确认。",java.util.List.of(),java.util.List.of(),java.util.List.of(),"ORDER_PREVIEWED",draft));
            var ai = new com.example.controller.AiController();
            org.springframework.test.util.ReflectionTestUtils.setField(ai,"agentRunService",runs);
            var mvc = OrderConfirmationMysqlIT.mvc(ai);
            var lostResponse = new java.util.concurrent.atomic.AtomicBoolean();
            var draftReadCount = new java.util.concurrent.atomic.AtomicInteger();
            Path dist = Path.of("../project-frontend/dist").toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(dist.resolve("index.html")), "请先构建前端");
            String token = UUID.randomUUID().toString();
            CountDownLatch finished = new CountDownLatch(1);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String type = "text/html; charset=utf-8";
                byte[] body;
                try {
                    if (path.equals("/__test/start")) {
                        body = ("<script>localStorage.setItem('shopping-agent-token','" + token + "');"
                                + "sessionStorage.setItem('shopping-agent-active-conversation','order-browser-001');location.replace('/assistant')</script>")
                                .getBytes(StandardCharsets.UTF_8);
                    } else if (path.equals("/__test/finish")) {
                        OrderConfirmationMysqlIT.assertConfirmedOnce();
                        assertTrue(lostResponse.get()); assertTrue(draftReadCount.get() >= 3);
                        assertEquals(1, OrderConfirmationMysqlIT.scalar("SELECT COUNT(*) FROM order_drafts"));
                        body = "浏览器确认验收完成：订单 1 笔、库存 8、购物车已清空，响应丢失后查回原订单。".getBytes(StandardCharsets.UTF_8);
                    } else if (path.startsWith("/api/")) {
                        type = "application/json; charset=utf-8";
                        if (! ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            status = 401;
                            body = "{}".getBytes(StandardCharsets.UTF_8);
                        } else if (path.equals("/api/auth/me")) {
                            body = "{\"code\":0,\"data\":{\"id\":1,\"username\":\"订单确认验收账号\",\"email\":\"test@example.invalid\"}}".getBytes(StandardCharsets.UTF_8);
                        } else {
                            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI())
                                    .requestAttr(Const.ATTR_USER_ID, 1L);
                            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                            if (contentType != null) request.contentType(contentType);
                            String confirmationKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                            if (confirmationKey != null) request.header("Idempotency-Key",confirmationKey);
                            request.content(exchange.getRequestBody().readAllBytes());
                            var response = mvc.perform(request).andReturn().getResponse();
                            status = response.getStatus();
                            body = response.getContentAsByteArray();
                            if (path.equals("/api/orders/drafts/" + draft.draftId())) draftReadCount.incrementAndGet();
                            if (path.endsWith("/confirm") && status == 201 && lostResponse.compareAndSet(false,true)) {
                                // 真实事务已提交，但浏览器只看到网关错误，必须 GET 查询原草稿恢复结果。
                                status = 504; body = "{\"code\":504,\"message\":\"模拟确认响应丢失\"}".getBytes(StandardCharsets.UTF_8);
                            }
                        }
                    } else {
                        Path file = dist.resolve(path.substring(1)).normalize();
                        if (!file.startsWith(dist)) throw new IllegalArgumentException("invalid path");
                        if (!Files.isRegularFile(file)) file = dist.resolve("index.html");
                        if (file.toString().endsWith(".js")) type = "text/javascript; charset=utf-8";
                        else if (file.toString().endsWith(".css")) type = "text/css; charset=utf-8";
                        else if (file.toString().endsWith(".svg")) type = "image/svg+xml";
                        body = Files.readAllBytes(file);
                    }
                } catch (Throwable e) {
                    status = 500;
                    body = "测试夹具请求失败，请检查测试日志".getBytes(StandardCharsets.UTF_8);
                    e.printStackTrace();
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                if (path.equals("/__test/finish") && status == 200) finished.countDown();
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/__test/start";
            Files.writeString(Path.of("target/order-browser-url.txt"), url);
            System.out.println("BROWSER_ORDER_CONFIRMATION_URL=" + url);
            assertTrue(finished.await(10, TimeUnit.MINUTES), "请在 10 分钟内完成 AI 跳转、显式确认、响应丢失恢复及刷新，然后打开 /__test/finish");
            OrderConfirmationMysqlIT.assertConfirmedOnce();
            System.out.println("BROWSER_ORDER_CONFIRMATION verified orders=1 stock=8 cartItems=0 originalDrafts=1 lostResponseRecovered=true");
        } finally {
            if (server != null) server.stop(0);
            OrderConfirmationMysqlIT.cleanup();
        }
    }
}
