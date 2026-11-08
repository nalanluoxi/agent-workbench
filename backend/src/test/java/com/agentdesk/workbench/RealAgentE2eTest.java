package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.database.ConnectionService;
import com.agentdesk.workbench.models.ModelService;
import com.agentdesk.workbench.sessions.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.RedisClient;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_E2E_TEST", matches = "1")
class RealAgentE2eTest {
    private static final Path TEMP = temp();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger TURNS = new AtomicInteger();
    private static Process redis;
    private static int redisPort;
    private static HttpServer model;
    @LocalServerPort int appPort;
    @Autowired JdbcTemplate db;
    @Autowired SecretVault vault;
    @Autowired ConnectionService connections;
    @Autowired SessionService sessions;
    @Autowired ModelService models;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeAll static void startDependencies() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) { redisPort = socket.getLocalPort(); }
        redis = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(redisPort), "--save", "", "--appendonly", "no", "--dir", TEMP.toString())
                .redirectErrorStream(true).redirectOutput(TEMP.resolve("redis.log").toFile()).start();
        for (int i = 0; i < 100; i++) {
            if (!redis.isAlive()) throw new IllegalStateException("临时 Redis 启动失败");
            try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", redisPort)) { break; } catch (java.io.IOException e) { Thread.sleep(30); }
        }
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            var request = JSON.readTree(exchange.getRequestBody());
            byte[] bytes;
            if (!request.path("stream").asBoolean()) {
                bytes = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", "{\"facts\":[],\"scenes\":[],\"persona\":[]}")))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            } else {
                Map<String, Object> delta;
                String finish;
                if (TURNS.getAndIncrement() == 0) {
                    String args = JSON.writeValueAsString(Map.of("toolId", ConnectionService.REDIS_TOOL, "command", "SET", "args", Map.of("key", "e2e-key", "value", "e2e-value")));
                    delta = Map.of("tool_calls", List.of(Map.of("index", 0, "id", "call-e2e", "type", "function", "function", Map.of("name", "database_operation", "arguments", args))));
                    finish = "tool_calls";
                } else { delta = Map.of("content", "已完成真实写入"); finish = "stop"; }
                String first = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("delta", delta))));
                String last = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of(), "finish_reason", finish))));
                bytes = ("data: " + first + "\n\ndata: " + last + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        model.start();
    }
    @AfterAll static void stop() throws Exception {
        if (model != null) model.stop(0);
        if (redis != null) { redis.destroy(); if (!redis.waitFor(5, TimeUnit.SECONDS)) redis.destroyForcibly(); }
    }

    @Test void realHttpAgentDelegatedCliConfirmationAndRedisCompleteEndToEnd() throws Exception {
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('e2e','e2e@example.com','unused',?)", now);
        db.update("INSERT INTO user_settings(user_id) VALUES('e2e')");
        db.update("INSERT INTO auth_tokens(token_hash,user_id,kind,expires_at,created_at) VALUES(?,'e2e','USER',?,?)", vault.digest("auth-token", "e2e-user-token"), now + 300_000, now);
        connections.create("e2e", new ConnectionService.ConnectionInput("REDIS", "临时 Redis", "127.0.0.1", redisPort, "0", "", ""));
        var modelConfig = models.create("e2e", new ModelService.ModelInput("CHAT", "本地测试模型", "compatible", "http://127.0.0.1:" + model.getAddress().getPort() + "/v1", "test-model", "", true));
        var session = sessions.create("e2e", "端到端");
        String response = send("POST", "/api/sessions/" + session.id() + "/runs", Map.of("message", "请写入 Redis", "modelId", modelConfig.id(), "mode", "BUILD"));
        String runId = JSON.readTree(response).path("id").asText();
        waitFor(runId, "waiting_confirmation");
        try (var client = RedisClient.create("redis://127.0.0.1:" + redisPort); var redisConnection = client.connect()) {
            assertThat(redisConnection.sync().get("e2e-key")).isNull();
            String actionId = db.queryForObject("SELECT a.id FROM db_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, runId);
            send("POST", "/api/actions/" + actionId + "/approve", Map.of());
            waitFor(runId, "completed");
            assertThat(redisConnection.sync().get("e2e-key")).isEqualTo("e2e-value");
            String stream = send("GET", "/api/runs/" + runId + "/stream?after=0", null);
            assertThat(stream).contains("confirmation_required", "tool_result", "run_completed");
            assertThat(sessions.messages("e2e", session.id())).extracting(SessionService.MessageView::content).contains("已完成真实写入");
        }
    }
    private String send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path)).header("Authorization", "Bearer e2e-user-token");
        if (body != null) request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        else request.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return response.body();
    }
    private void waitFor(String runId, String expected) throws Exception {
        for (int i = 0; i < 300; i++) {
            String status = db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, runId);
            if (expected.equals(status)) return;
            if (List.of("failed", "interrupted").contains(status)) throw new AssertionError("运行失败：" + db.queryForObject("SELECT error FROM runs WHERE id=?", String.class, runId));
            Thread.sleep(100);
        }
        throw new AssertionError("运行没有进入：" + expected);
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-e2e-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
