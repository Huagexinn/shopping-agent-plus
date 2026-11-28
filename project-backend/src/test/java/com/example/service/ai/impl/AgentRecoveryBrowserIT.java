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

/** 显式启动的浏览器验收夹具：真实前端 dist、MVC/购物车服务、隔离 MySQL；仅登录身份为测试替身。 */
@EnabledIfEnvironmentVariable(named = "SHOPPING_TEST_MYSQL_URL", matches = ".+")
class AgentRecoveryBrowserIT {
    @Test
    void browserRecoveryAcceptance() throws Exception {
        var fixture = new AgentRecoveryMysqlIT();
        HttpServer server = null;
        try {
            AgentRecoveryMysqlIT.setup();
            fixture.reset();
            fixture.interrupted("CartTool", "{\"request\":{\"operation\":\"add\",\"productId\":1,\"quantity\":1}}");
            var mvc = fixture.mvc();
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
                                + "sessionStorage.setItem('shopping-agent-active-conversation','conversation-001');location.replace('/assistant')</script>")
                                .getBytes(StandardCharsets.UTF_8);
                    } else if (path.equals("/__test/finish")) {
                        fixture.assertRecoveredOnce();
                        body = "浏览器恢复验收完成：购物车数量 1、业务操作记录 1、库存保持 10。".getBytes(StandardCharsets.UTF_8);
                    } else if (path.startsWith("/api/")) {
                        type = "application/json; charset=utf-8";
                        if (! ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            status = 401;
                            body = "{}".getBytes(StandardCharsets.UTF_8);
                        } else if (path.equals("/api/auth/me")) {
                            body = "{\"code\":0,\"data\":{\"id\":1,\"username\":\"恢复验收账号\",\"email\":\"test@example.invalid\"}}".getBytes(StandardCharsets.UTF_8);
                        } else {
                            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI())
                                    .requestAttr(Const.ATTR_USER_ID, 1L);
                            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                            if (contentType != null) request.contentType(contentType);
                            request.content(exchange.getRequestBody().readAllBytes());
                            var response = mvc.perform(request).andReturn().getResponse();
                            status = response.getStatus();
                            body = response.getContentAsByteArray();
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
            Files.writeString(Path.of("target/recovery-browser-url.txt"), url);
            System.out.println("BROWSER_RECOVERY_URL=" + url);
            assertTrue(finished.await(10, TimeUnit.MINUTES), "请在 10 分钟内完成恢复、刷新及购物车核对，然后打开 /__test/finish");
            fixture.assertRecoveredOnce();
            System.out.println("BROWSER_RECOVERY verified actualQuantity=1 operationRecords=1 stock=10");
        } finally {
            if (server != null) server.stop(0);
            AgentRecoveryMysqlIT.cleanup();
        }
    }
}
