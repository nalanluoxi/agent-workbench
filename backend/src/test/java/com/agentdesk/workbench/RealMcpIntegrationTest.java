package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.mcp.McpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_MCP_TEST", matches = "1")
class RealMcpIntegrationTest {
    private static final Path TEMP = temp();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer server;
    @Autowired JdbcTemplate db;
    @Autowired McpService mcp;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!"Bearer integration-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
            if (exchange.getRequestMethod().equals("DELETE")) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
            var request = JSON.readTree(exchange.getRequestBody()); String method = request.path("method").asText();
            if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            Object result;
            if (method.equals("initialize")) { exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1"); result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "serverInfo", Map.of("name", "test", "version", "1")); }
            else if (method.equals("tools/list")) result = Map.of("tools", java.util.List.of(Map.of("name", "echo", "description", "echo test", "inputSchema", Map.of("type", "object"))));
            else if (method.equals("tools/call")) { CALLS.incrementAndGet(); result = Map.of("content", java.util.List.of(Map.of("type", "text", "text", request.path("params").path("arguments").path("message").asText()))); }
            else { exchange.sendResponseHeaders(400, -1); exchange.close(); return; }
            byte[] payload = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.path("id").asLong(), "result", result));
            if (method.equals("tools/list")) { exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); payload = ("event: message\ndata: " + new String(payload, StandardCharsets.UTF_8) + "\n\n").getBytes(StandardCharsets.UTF_8); }
            else exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload); exchange.close();
        });
        server.start();
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test void discoverAndCallOverHttpOnlyAfterHumanApproval() {
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('mcp-user','mcp@example.com','unused',?)", now);
        db.update("INSERT INTO user_settings(user_id) VALUES('mcp-user')");
        var config = mcp.create("mcp-user", new McpService.ServerInput("测试 MCP", "HTTP", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "", java.util.List.of(), "integration-secret", true));
        assertThat(config.hasToken()).isTrue();
        assertThat(mcp.refresh("mcp-user", config.id())).extracting(McpService.ToolView::name).containsExactly("echo");
        Principal user = new Principal("mcp-user", "USER", "test");
        var pending = mcp.request(user, new McpService.CallInput(config.id(), "echo", json.valueToTree(Map.of("message", "hello")), null));
        assertThat(pending.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(pending.action().kind()).isEqualTo("MCP");
        assertThat(CALLS.get()).isZero();
        assertThatThrownBy(() -> mcp.approve(new Principal("mcp-user", "AGENT", "test", "run"), pending.action().id())).isInstanceOf(ApiException.class);
        var result = mcp.approve(user, pending.action().id());
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result().path("content").path(0).path("text").asText()).isEqualTo("hello");
        mcp.approve(user, pending.action().id());
        assertThat(CALLS.get()).isEqualTo(1);
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-real-mcp-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
