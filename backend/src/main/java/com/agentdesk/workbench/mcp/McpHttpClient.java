package com.agentdesk.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class McpHttpClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final ExecutorService readers = Executors.newCachedThreadPool();
    public McpHttpClient(ObjectMapper json) { this.json = json; }

    public List<Tool> discover(String url, String token) {
        try (Session session = open(url, token)) {
            List<Tool> tools = new ArrayList<>(); Set<String> names = new HashSet<>(); String cursor = null;
            for (int page = 0; page < 20; page++) {
                JsonNode result = session.request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
                JsonNode list = result.path("tools");
                if (!list.isArray()) throw new IllegalStateException("MCP 服务返回的工具列表无效");
                for (JsonNode tool : list) {
                    String name = tool.path("name").asText(), description = tool.path("description").asText("");
                    JsonNode schema = tool.path("inputSchema");
                    if (name.isBlank() || name.length() > 128 || description.length() > 2000 || !schema.isObject()) throw new IllegalStateException("MCP 工具定义无效");
                    if (!names.add(name)) throw new IllegalStateException("MCP 服务返回了重复的工具名称：" + name);
                    tools.add(new Tool(name, description, schema));
                    if (tools.size() > 500) throw new IllegalStateException("MCP 工具数量超过限制");
                }
                cursor = result.path("nextCursor").asText("");
                if (cursor.length() > 2048) throw new IllegalStateException("MCP 分页游标过长");
                if (cursor.isBlank()) return tools;
            }
            throw new IllegalStateException("MCP 工具分页超过限制");
        }
    }

    public JsonNode call(String url, String token, String name, JsonNode args) {
        try (Session session = open(url, token)) { return session.request("tools/call", Map.of("name", name, "arguments", args)); }
    }

    private Session open(String url, String token) {
        Session session = new Session(url, token);
        try {
            JsonNode result = session.request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "agent-workbench", "version", "0.1.0")));
            String version = result.path("protocolVersion").asText();
            if (!Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(version)) throw new IllegalStateException("MCP 协议版本不受支持：" + version);
            session.version = version;
            session.notification("notifications/initialized");
            return session;
        } catch (RuntimeException e) {
            session.close();
            throw e;
        }
    }

    private final class Session implements AutoCloseable {
        final String url, token; String sessionId, version; long nextId = 1;
        Session(String url, String token) { this.url = url; this.token = token; }
        JsonNode request(String method, Map<String, Object> params) {
            long id = nextId++;
            Map<String, Object> body = new LinkedHashMap<>(); body.put("jsonrpc", "2.0"); body.put("id", id); body.put("method", method); body.put("params", params);
            try {
                HttpResponse<InputStream> response = send("POST", json.writeValueAsString(body));
                try (InputStream stream = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("MCP 请求失败（HTTP " + response.statusCode() + "）");
                    response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
                    String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    JsonNode root = readWithTimeout(stream, type.contains("text/event-stream"), id);
                    if (root == null || !root.path("id").asText().equals(Long.toString(id))) throw new IllegalStateException("MCP 响应 ID 不匹配");
                    if (root.has("error")) throw new IllegalStateException("MCP 服务返回错误（" + root.path("error").path("code").asText("未知") + "）");
                    if (!root.path("result").isObject()) throw new IllegalStateException("MCP 响应缺少结果");
                    return root.path("result");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("MCP 请求已中断", e); }
            catch (java.io.IOException e) { throw new IllegalStateException("无法连接 MCP 服务", e); }
        }
        void notification(String method) {
            try {
                HttpResponse<InputStream> response = send("POST", json.writeValueAsString(Map.of("jsonrpc", "2.0", "method", method)));
                try (InputStream body = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("MCP 初始化通知失败（HTTP " + response.statusCode() + "）");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("MCP 请求已中断", e); }
            catch (java.io.IOException e) { throw new IllegalStateException("无法连接 MCP 服务", e); }
        }
        HttpResponse<InputStream> send(String method, String body) throws java.io.IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json");
            if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
            if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
            if (version != null) request.header("MCP-Protocol-Version", version);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        }
        @Override public void close() {
            if (sessionId == null) return;
            try { HttpResponse<InputStream> response = send("DELETE", null); response.body().close(); }
            catch (Exception ignored) { /* 会话由服务端超时回收 */ }
        }
    }

    private JsonNode readSse(InputStream stream, long expectedId) throws java.io.IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(limited(stream), StandardCharsets.UTF_8))) {
            String line; StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) { JsonNode event = json.readTree(data.toString()); data.setLength(0); if (event.path("id").asText().equals(Long.toString(expectedId))) return event; }
                } else if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).stripLeading()); }
            }
            throw new IllegalStateException("MCP 事件流未返回对应响应");
        }
    }
    private JsonNode readWithTimeout(InputStream stream, boolean sse, long id) throws java.io.IOException, InterruptedException {
        var future = readers.submit(() -> sse ? readSse(stream, id) : json.readTree(limited(stream)));
        try { return future.get(30, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { stream.close(); future.cancel(true); throw new IllegalStateException("MCP 响应超时", e); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.io.IOException io) throw io;
            throw new IllegalStateException("无法解析 MCP 响应", cause);
        }
    }
    private InputStream limited(InputStream input) {
        return new java.io.FilterInputStream(input) {
            long count;
            @Override public int read() throws java.io.IOException { int value = super.read(); if (value >= 0 && ++count > 4L * 1024 * 1024) throw new java.io.IOException("MCP 响应过大"); return value; }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException { int n = super.read(b, off, len); if (n > 0 && (count += n) > 4L * 1024 * 1024) throw new java.io.IOException("MCP 响应过大"); return n; }
        };
    }
    public record Tool(String name, String description, JsonNode inputSchema) {}
    @PreDestroy public void shutdown() { readers.shutdownNow(); }
}
