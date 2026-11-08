package com.agentdesk.workbench.mcp;

import com.agentdesk.workbench.process.RestrictedProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** MCP 2025 JSON-RPC over newline-delimited stdin/stdout, with a persistent session per server. */
@Component
public class McpStdioClient {
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private final RestrictedProcess sandbox;
    private final ObjectMapper json;
    private final Map<String, Session> sessions = new HashMap<>();

    public McpStdioClient(RestrictedProcess sandbox, ObjectMapper json) { this.sandbox = sandbox; this.json = json; }

    public RestrictedProcess.Plan prepare(String command, List<String> args) { return sandbox.mcp(command, args); }

    public synchronized List<McpHttpClient.Tool> discover(String id, RestrictedProcess.Plan plan) {
        Session session = session(id, plan);
        try {
            List<McpHttpClient.Tool> result = new ArrayList<>(); String cursor = null;
            for (int page = 0; page < 20; page++) {
                JsonNode response = session.request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
                if (!response.path("tools").isArray()) throw new IllegalStateException("MCP 服务返回的工具列表无效");
                for (JsonNode item : response.path("tools")) {
                    String name = item.path("name").asText(), description = item.path("description").asText("");
                    JsonNode schema = item.path("inputSchema");
                    if (name.isBlank() || name.length() > 128 || description.length() > 2000 || !schema.isObject()) throw new IllegalStateException("MCP 工具定义无效");
                    result.add(new McpHttpClient.Tool(name, description, schema));
                    if (result.size() > 500) throw new IllegalStateException("MCP 工具数量超过限制");
                }
                cursor = response.path("nextCursor").asText("");
                if (cursor.isBlank()) return result;
            }
            throw new IllegalStateException("MCP 工具分页超过限制");
        } catch (RuntimeException e) { invalidate(id); throw e; }
    }

    public synchronized JsonNode call(String id, RestrictedProcess.Plan plan, String name, JsonNode args) {
        try { return session(id, plan).request("tools/call", Map.of("name", name, "arguments", args)); }
        catch (RuntimeException e) { invalidate(id); throw e; }
    }

    public synchronized void invalidate(String id) { Session session = sessions.remove(id); if (session != null) session.close(); }
    @PreDestroy public synchronized void shutdown() { sessions.values().forEach(Session::close); sessions.clear(); }

    private Session session(String id, RestrictedProcess.Plan plan) {
        Session existing = sessions.get(id);
        if (existing != null && existing.alive() && existing.plan.equals(plan)) return existing;
        invalidate(id);
        Session created = new Session(plan);
        try {
            JsonNode hello = created.request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "agent-workbench", "version", "0.1.0")));
            String version = hello.path("protocolVersion").asText();
            if (!Set.of("2025-03-26", "2025-06-18", "2025-11-25").contains(version)) throw new IllegalStateException("MCP 协议版本不受支持：" + version);
            created.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            sessions.put(id, created);
            return created;
        } catch (RuntimeException e) { created.close(); throw e; }
    }

    private final class Session implements AutoCloseable {
        final RestrictedProcess.Plan plan;
        final Process process;
        final BlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(64);
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        long id;

        Session(RestrictedProcess.Plan plan) {
            this.plan = plan;
            try {
                process = sandbox.process(plan).start();
                Thread stdout = new Thread(() -> readFrames(process.getInputStream()), "mcp-stdio-output"); stdout.setDaemon(true); stdout.start();
                Thread stderr = new Thread(() -> drain(process.getErrorStream()), "mcp-stdio-errors"); stderr.setDaemon(true); stderr.start();
            } catch (Exception e) { throw new IllegalStateException("无法启动受限 MCP STDIO 服务：" + e.getMessage(), e); }
        }
        boolean alive() { return process.isAlive(); }
        JsonNode request(String method, Map<String, Object> params) {
            long requestId = ++id;
            send(Map.of("jsonrpc", "2.0", "id", requestId, "method", method, "params", params));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            try {
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IllegalStateException("MCP STDIO 响应超时");
                    byte[] bytes = frames.poll(remaining, TimeUnit.NANOSECONDS);
                    if (bytes == null) throw new IllegalStateException("MCP STDIO 响应超时");
                    if (bytes.length == 0) throw new IllegalStateException(diagnostic());
                    JsonNode message = json.readTree(bytes);
                    if (message.has("method")) { handleServerMessage(message); continue; }
                    if (!message.path("id").asText().equals(Long.toString(requestId))) continue;
                    if (message.has("error")) throw new IllegalStateException("MCP 服务返回错误（" + message.path("error").path("code").asText("未知") + "）");
                    if (!message.path("result").isObject()) throw new IllegalStateException("MCP 响应缺少结果");
                    return message.path("result");
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("MCP STDIO 调用已中断", e); }
            catch (java.io.IOException e) { throw new IllegalStateException("MCP STDIO 响应格式无效", e); }
        }
        void handleServerMessage(JsonNode message) {
            if (!message.has("id")) return;
            Object requestId = json.convertValue(message.get("id"), Object.class);
            if (message.path("method").asText().equals("ping")) send(Map.of("jsonrpc", "2.0", "id", requestId, "result", Map.of()));
            else send(Map.of("jsonrpc", "2.0", "id", requestId, "error", Map.of("code", -32601, "message", "Unsupported server request")));
        }
        void send(Object body) {
            try {
                byte[] frame = json.writeValueAsBytes(body);
                if (frame.length > MAX_FRAME) throw new IllegalStateException("MCP 请求过大");
                OutputStream input = process.getOutputStream(); input.write(frame); input.write('\n'); input.flush();
            } catch (java.io.IOException e) { throw new IllegalStateException("MCP STDIO 服务不可用", e); }
        }
        void readFrames(InputStream in) {
            try (in) {
                ByteArrayOutputStream line = new ByteArrayOutputStream(); int b;
                while ((b = in.read()) != -1) {
                    if (b == '\n') { if (line.size() > 0 && !frames.offer(line.toByteArray())) break; line.reset(); }
                    else { if (line.size() >= MAX_FRAME) break; line.write(b); }
                }
            } catch (Exception ignored) { /* Unavailable service is reported by the caller. */ }
            finally { frames.offer(new byte[0]); }
        }
        void drain(InputStream in) {
            try (in) {
                byte[] buffer = new byte[1024]; int n;
                while ((n = in.read(buffer)) != -1) synchronized (errors) { int available = 8192 - errors.size(); if (available > 0) errors.write(buffer, 0, Math.min(n, available)); }
            } catch (Exception ignored) { }
        }
        String diagnostic() {
            String text; synchronized (errors) { text = errors.toString(StandardCharsets.UTF_8); }
            if (text.contains("ModuleNotFoundError") || text.contains("ERR_MODULE_NOT_FOUND") || text.contains("Cannot find module")) return "MCP 服务缺少脚本依赖，请检查运行环境和程序目录";
            if (text.contains("Operation not permitted") || text.contains("Permission denied")) return "MCP 服务尝试访问隔离范围以外的资源";
            return "MCP STDIO 服务已退出或输出无效，请检查脚本、依赖和 JSON-RPC 格式";
        }
        @Override public void close() { process.destroy(); try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); } }
    }
}
