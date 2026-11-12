package com.agentdesk.workbench.agent;

import com.agentdesk.workbench.models.ModelErrorMessage;
import com.agentdesk.workbench.models.OpenAiCompatibleClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class ModelGateway {
    private static final int MAX_CONTENT_CHARS = 1024 * 1024;
    private static final int MAX_TOOL_CALLS = 64;
    private static final int MAX_TOOL_ARGUMENT_CHARS = 1024 * 1024;
    private final OpenAiCompatibleClients clients;
    public ModelGateway(OpenAiCompatibleClients clients) { this.clients = clients; }

    public ModelReply complete(String baseUrl, String model, String apiKey, ArrayNode messages, String mode,
                               Set<String> enabledDatabaseTypes, Set<String> routeCandidates, Consumer<String> onText) {
        try {
            ChatRequest request = ChatRequest.builder().messages(chatMessages(messages))
                    .toolSpecifications(toolSpecifications(mode, enabledDatabaseTypes, routeCandidates)).build();
            ChatResponse response = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                AtomicBoolean receivedText = new AtomicBoolean(false);
                try {
                    response = request(baseUrl, model, apiKey, request, text -> {
                        receivedText.set(true);
                        onText.accept(text);
                    });
                    break;
                } catch (Exception error) {
                    if (attempt == 0 && !receivedText.get() && isEarlyConnectionClose(error)) continue;
                    throw error;
                }
            }
            if (response == null) throw new IllegalStateException("模型连接提前关闭");
            AiMessage message = response.aiMessage();
            String content = message.text() == null ? "" : message.text();
            if (content.length() > MAX_CONTENT_CHARS) throw new IllegalStateException("模型返回文本超过限制");
            List<ToolCall> calls = message.toolExecutionRequests().stream()
                    .map(call -> new ToolCall(call.id(), call.name(), call.arguments())).toList();
            return buildReply(content, calls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型请求已中断", e);
        } catch (Exception e) { throw ModelErrorMessage.failure("模型请求", e); }
    }

    private ChatResponse request(String baseUrl, String model, String apiKey, ChatRequest request,
                                 Consumer<String> onText) throws Exception {
            CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
            clients.streaming(baseUrl, model, apiKey).chat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String text) { if (text != null && !text.isEmpty()) onText.accept(text); }
                @Override public void onCompleteResponse(ChatResponse response) { completed.complete(response); }
                @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
            });
        return completed.get(3, TimeUnit.MINUTES);
    }

    private boolean isEarlyConnectionClose(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message != null && message.trim().equalsIgnoreCase("closed");
    }

    private List<ChatMessage> chatMessages(ArrayNode source) {
        List<ChatMessage> result = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        for (JsonNode item : source) {
            String role = item.path("role").asText();
            String content = item.path("content").isTextual() ? item.path("content").asText() : "";
            if (role.equals("system")) result.add(SystemMessage.from(content));
            else if (role.equals("user")) result.add(UserMessage.from(content));
            else if (role.equals("assistant")) {
                List<ToolExecutionRequest> requests = new ArrayList<>();
                for (JsonNode call : item.path("tool_calls")) {
                    String id = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String arguments = call.path("function").path("arguments").asText("{}");
                    requests.add(ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build());
                    toolNames.put(id, name);
                }
                result.add(AiMessage.from(content, requests));
            } else if (role.equals("tool")) {
                String id = item.path("tool_call_id").asText();
                result.add(ToolExecutionResultMessage.from(id, toolNames.getOrDefault(id, "tool"), content));
            }
        }
        return result;
    }

    List<ToolSpecification> toolSpecifications(String mode, Set<String> databaseTypes, Set<String> candidates) {
        List<Map<String, Object>> definitions = tools(mode, databaseTypes, candidates);
        List<ToolSpecification> result = new ArrayList<>();
        for (Map<String, Object> wrapper : definitions) {
            @SuppressWarnings("unchecked") Map<String, Object> function = (Map<String, Object>) wrapper.get("function");
            @SuppressWarnings("unchecked") Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
            result.add(ToolSpecification.builder().name((String) function.get("name")).description((String) function.get("description"))
                    .parameters(schema(parameters)).build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private JsonObjectSchema schema(Map<String, Object> source) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder().additionalProperties(Boolean.TRUE.equals(source.get("additionalProperties")));
        Map<String, Object> properties = (Map<String, Object>) source.getOrDefault("properties", Map.of());
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> property = (Map<String, Object>) entry.getValue();
            String description = (String) property.get("description");
            Object element;
            if (property.containsKey("enum")) element = JsonEnumSchema.builder().enumValues((List<String>) property.get("enum")).description(description).build();
            else if ("object".equals(property.get("type"))) element = JsonObjectSchema.builder().description(description).additionalProperties(true).build();
            else if ("array".equals(property.get("type"))) element = JsonArraySchema.builder().description(description).items(new JsonStringSchema()).build();
            else element = JsonStringSchema.builder().description(description).build();
            builder.addProperty(entry.getKey(), (dev.langchain4j.model.chat.request.json.JsonSchemaElement) element);
        }
        Object required = source.get("required");
        if (required instanceof List<?> list) builder.required(list.stream().map(Object::toString).toList());
        return builder.build();
    }

    private ModelReply buildReply(String content, List<ToolCall> calls) {
        if (content.isBlank() && calls.isEmpty()) throw new IllegalStateException("模型没有返回可用内容");
        if (calls.size() > MAX_TOOL_CALLS) throw new IllegalStateException("模型返回的工具调用数量超过限制");
        for (ToolCall call : calls) {
            if (call.id().isBlank() || call.name().isBlank()) throw new IllegalStateException("模型返回的工具调用不完整");
            if (call.name().length() > 128 || call.arguments().length() > MAX_TOOL_ARGUMENT_CHARS) throw new IllegalStateException("模型返回的工具调用超过限制");
        }
        return new ModelReply(content, calls);
    }

    private List<Map<String, Object>> tools(String mode, Set<String> enabledDatabaseTypes, Set<String> routeCandidates) {
        boolean build = "BUILD".equalsIgnoreCase(mode);
        List<String> toolIds = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        if (enabledDatabaseTypes.contains("MYSQL")) {
            toolIds.add("builtin-mysql");
            commands.addAll(com.agentdesk.workbench.database.OperationPolicy.MYSQL_READS);
            commands.add("SQL");
            if (build) commands.addAll(com.agentdesk.workbench.database.OperationPolicy.MYSQL_WRITES);
        }
        if (enabledDatabaseTypes.contains("REDIS")) {
            toolIds.add("builtin-redis");
            commands.addAll(com.agentdesk.workbench.database.OperationPolicy.REDIS_READS);
            if (build) commands.addAll(com.agentdesk.workbench.database.OperationPolicy.REDIS_WRITES);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (!toolIds.isEmpty()) {
            Map<String, Object> properties = Map.of("toolId", Map.of("type", "string", "enum", toolIds, "description", "固定内置工具 ID"),
                "command", Map.of("type", "string", "enum", commands.stream().distinct().sorted().toList(), "description", "当前模式允许的操作。MySQL 多行 INSERT 使用 INSERT 命令并传 columns 与二维 values；任意单条 SQL/DDL 使用 SQL 命令。"),
                "args", Map.of("type", "object", "description", "严格参数格式：MySQL 单行 INSERT={database?,table,values对象}；多行 INSERT={database?,table,columns字符串数组,values二维数组}；SQL={database?,sql}；SELECT={database?,table,columns?,where?,limit?}。Redis SCAN={database?,pattern?,limit?,cursor?}；DBSIZE={database?}；其他元信息命令={database?,key}。", "additionalProperties", true));
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

    public record ToolCall(String id, String name, String arguments) {}
    public record ModelReply(String content, List<ToolCall> calls) {}
}
