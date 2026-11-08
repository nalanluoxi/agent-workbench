package com.agentdesk.workbench.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class ModelGateway {
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 1024 * 1024;
    private static final int MAX_TOOL_CALLS = 64;
    private static final int MAX_TOOL_ARGUMENT_CHARS = 1024 * 1024;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public ModelGateway(ObjectMapper json) { this.json = json; }

    public ModelReply complete(String baseUrl, String model, String apiKey, ArrayNode messages, String mode,
                               Set<String> enabledDatabaseTypes, Set<String> routeCandidates, Consumer<String> onText) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            List<Map<String, Object>> tools = tools(mode, enabledDatabaseTypes, routeCandidates);
            if (!tools.isEmpty()) body.put("tools", tools);
            body.put("stream", true);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMinutes(3)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
            HttpResponse<java.io.InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("模型请求失败（HTTP " + response.statusCode() + "）");
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (type.contains("text/event-stream")) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(limited(stream), StandardCharsets.UTF_8))) {
                        return parseStream(reader, onText);
                    }
                }
                JsonNode root = json.readTree(limited(stream));
                return parseComplete(root, onText);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型请求已中断", e);
        } catch (java.io.IOException e) { throw new IllegalStateException("模型连接失败，请检查所选模型配置", e); }
    }

    ModelReply parseStream(BufferedReader reader, Consumer<String> onText) throws java.io.IOException {
        StringBuilder content = new StringBuilder();
        Map<Integer, MutableCall> calls = new LinkedHashMap<>();
        String line;
        boolean complete = false;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            if (payload.equals("[DONE]")) { complete = true; continue; }
            JsonNode root = json.readTree(payload);
            if (root.has("error")) throw new IllegalStateException("模型返回错误，请检查模型配置");
            JsonNode choice = root.path("choices").path(0);
            if (choice.path("finish_reason").isTextual()) complete = true;
            JsonNode delta = choice.path("delta");
            if (delta.path("content").isTextual()) {
                String part = delta.path("content").asText();
                if (content.length() + part.length() > MAX_CONTENT_CHARS) throw new IllegalStateException("模型返回文本超过限制");
                content.append(part); onText.accept(part);
            }
            for (JsonNode item : delta.path("tool_calls")) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= MAX_TOOL_CALLS) throw new IllegalStateException("模型返回的工具调用索引无效");
                MutableCall call = calls.computeIfAbsent(index, ignored -> new MutableCall());
                if (item.path("id").isTextual()) call.id = item.path("id").asText();
                if (item.path("function").path("name").isTextual()) call.name.append(item.path("function").path("name").asText());
                if (item.path("function").path("arguments").isTextual()) call.arguments.append(item.path("function").path("arguments").asText());
                if (call.name.length() > 128 || call.arguments.length() > MAX_TOOL_ARGUMENT_CHARS) throw new IllegalStateException("模型返回的工具调用超过限制");
            }
        }
        if (!complete) throw new IllegalStateException("模型响应中断，请重试或检查所选模型");
        return buildReply(content.toString(), calls.values().stream().map(MutableCall::build).toList());
    }

    private ModelReply parseComplete(JsonNode root, Consumer<String> onText) {
        if (root == null || !root.isObject()) throw new IllegalStateException("模型响应格式无效");
        if (root.has("error")) throw new IllegalStateException("模型返回错误，请检查模型配置");
        JsonNode message = root.path("choices").path(0).path("message");
        if (!message.isObject()) throw new IllegalStateException("模型响应缺少消息内容");
        String content = message.path("content").isTextual() ? message.path("content").asText() : "";
        if (content.length() > MAX_CONTENT_CHARS) throw new IllegalStateException("模型返回文本超过限制");
        if (!content.isEmpty()) onText.accept(content);
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            if (calls.size() >= MAX_TOOL_CALLS) throw new IllegalStateException("模型返回的工具调用数量超过限制");
            String arguments = call.path("function").path("arguments").asText();
            if (arguments.length() > MAX_TOOL_ARGUMENT_CHARS) throw new IllegalStateException("模型返回的工具调用参数超过限制");
            calls.add(new ToolCall(call.path("id").asText(), call.path("function").path("name").asText(), arguments));
        }
        return buildReply(content, calls);
    }

    private ModelReply buildReply(String content, List<ToolCall> calls) {
        if (content.isBlank() && calls.isEmpty()) throw new IllegalStateException("模型没有返回可用内容");
        for (ToolCall call : calls) if (call.id().isBlank() || call.name().isBlank()) throw new IllegalStateException("模型返回的工具调用不完整");
        return new ModelReply(content, calls);
    }

    private List<Map<String, Object>> tools(String mode, Set<String> enabledDatabaseTypes, Set<String> routeCandidates) {
        boolean build = "BUILD".equalsIgnoreCase(mode);
        List<String> toolIds = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        if (enabledDatabaseTypes.contains("MYSQL") && routeCandidates.contains("builtin-mysql")) {
            toolIds.add("builtin-mysql");
            commands.addAll(com.agentdesk.workbench.database.OperationPolicy.MYSQL_READS);
            commands.add("SQL");
            if (build) commands.addAll(com.agentdesk.workbench.database.OperationPolicy.MYSQL_WRITES);
        }
        if (enabledDatabaseTypes.contains("REDIS") && routeCandidates.contains("builtin-redis")) {
            toolIds.add("builtin-redis");
            commands.addAll(com.agentdesk.workbench.database.OperationPolicy.REDIS_READS);
            if (build) commands.addAll(com.agentdesk.workbench.database.OperationPolicy.REDIS_WRITES);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (!toolIds.isEmpty()) {
            Map<String, Object> properties = Map.of("toolId", Map.of("type", "string", "enum", toolIds, "description", "固定内置工具 ID"),
                "command", Map.of("type", "string", "enum", commands.stream().distinct().sorted().toList(), "description", "当前模式允许的结构化操作"),
                "args", Map.of("type", "object", "description", "操作参数", "additionalProperties", true));
            result.add(Map.of("type", "function", "function", Map.of("name", "database_operation",
                    "description", build ? "调用固定 MySQL/Redis CLI。所有数据变更都需要用户二次确认。" : "调用固定 MySQL/Redis CLI；Chat 模式仅允许读取。",
                    "parameters", Map.of("type", "object", "properties", properties, "required", List.of("toolId", "command", "args")))));
        }
        boolean skillCandidate = routeCandidates.stream().anyMatch(value -> value.startsWith("skill:"));
        boolean mcpCandidate = routeCandidates.stream().anyMatch(value -> value.startsWith("mcp:"));
        if (build && skillCandidate) {
            result.add(Map.of("type", "function", "function", Map.of("name", "read_skill", "description", "读取本次路由候选中已启用技能的说明。",
                            "parameters", Map.of("type", "object", "properties", Map.of("skillId", Map.of("type", "string")), "required", List.of("skillId")))));
            result.add(Map.of("type", "function", "function", Map.of("name", "skill_script", "description", "申请执行路由候选技能中的脚本；需用户确认，脚本没有网络和宿主文件写入权限。",
                            "parameters", Map.of("type", "object", "properties", Map.of("skillId", Map.of("type", "string"), "script", Map.of("type", "string"), "args", Map.of("type", "array", "items", Map.of("type", "string")), "stdin", Map.of("type", "string")), "required", List.of("skillId", "script", "args")))));
        }
        if (mcpCandidate) {
            result.add(Map.of("type", "function", "function", Map.of("name", "mcp_operation", "description", build ? "调用本次路由候选中的 MCP 工具；调用前必须由用户确认。" : "调用本次路由候选中明确标记为只读的 MCP 工具。",
                            "parameters", Map.of("type", "object", "properties", Map.of("serverId", Map.of("type", "string"), "toolName", Map.of("type", "string"), "args", Map.of("type", "object", "additionalProperties", true)), "required", List.of("serverId", "toolName", "args")))));
        }
        return result;
    }

    private java.io.InputStream limited(java.io.InputStream input) {
        return new java.io.FilterInputStream(input) {
            long count;
            @Override public int read() throws java.io.IOException {
                int value = super.read();
                if (value >= 0 && ++count > MAX_RESPONSE_BYTES) throw new java.io.IOException("模型响应过大");
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                int read = super.read(buffer, offset, length);
                if (read > 0 && (count += read) > MAX_RESPONSE_BYTES) throw new java.io.IOException("模型响应过大");
                return read;
            }
        };
    }

    private static class MutableCall {
        String id = "";
        StringBuilder name = new StringBuilder();
        StringBuilder arguments = new StringBuilder();
        ToolCall build() { return new ToolCall(id, name.toString(), arguments.toString()); }
    }
    public record ToolCall(String id, String name, String arguments) {}
    public record ModelReply(String content, List<ToolCall> calls) {}
}
