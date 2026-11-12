package com.agentdesk.workbench.database;

import com.agentdesk.workbench.core.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OperationPolicy {
    public static final Set<String> MYSQL_READS = Set.of("DATABASES", "TABLES", "DESCRIBE", "SELECT");
    public static final Set<String> MYSQL_WRITES = Set.of("INSERT", "UPDATE", "DELETE");
    public static final Set<String> REDIS_READS = Set.of(
            "SCAN", "TYPE", "EXISTS", "DBSIZE", "TTL", "PTTL",
            "GET", "STRLEN", "HGET", "HGETALL", "HLEN", "LRANGE", "LLEN",
            "SMEMBERS", "SCARD", "ZRANGE", "ZCARD", "XRANGE", "XLEN");
    public static final Set<String> REDIS_WRITES = Set.of("SET", "DEL", "EXPIRE", "HSET", "HDEL", "LPUSH", "RPUSH", "LSET", "LREM", "SADD", "SREM", "ZADD", "ZREM", "XADD", "XDEL");
    private final SqlGuard sqlGuard;

    public OperationPolicy(SqlGuard sqlGuard) { this.sqlGuard = sqlGuard; }

    public Operation prepare(String type, OperationRequest input) {
        return prepare(type, input, "BUILD");
    }

    public Operation prepare(String type, OperationRequest input, String mode) {
        if (input == null || input.command() == null) throw ApiException.badRequest("工具和操作不能为空");
        String normalizedMode = mode == null ? "BUILD" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("CHAT", "BUILD").contains(normalizedMode)) throw ApiException.badRequest("运行模式必须是 CHAT 或 BUILD");
        String command = input.command().trim().toUpperCase(java.util.Locale.ROOT);
        JsonNode args = input.args() == null ? JsonNodeFactory.instance.objectNode() : input.args();
        if (!args.isObject()) throw ApiException.badRequest("操作参数必须是对象");
        ObjectNode validated = ((ObjectNode) args).deepCopy();
        JsonNode database = validated.remove("database");
        if ("MYSQL".equals(type)) {
            if ("SQL".equals(command)) {
                SqlGuard.GuardResult parsed = sqlGuard.inspect(args.path("sql").asText(""), 500);
                if (parsed.write() && "CHAT".equals(normalizedMode)) throw ApiException.forbidden("Chat 模式只允许 MySQL 只读 SQL");
                ObjectNode enriched = ((ObjectNode) args).deepCopy();
                enriched.put("sql", parsed.sql());
                enriched.put("risk", parsed.risk());
                return new Operation(command, enriched, parsed.write());
            }
            if (!MYSQL_READS.contains(command) && !MYSQL_WRITES.contains(command)) throw ApiException.badRequest("不支持该 MySQL 操作，请使用受支持的结构化操作");
            validateDatabase(type, database);
            validateMysql(command, validated);
            boolean write = MYSQL_WRITES.contains(command);
            if (write && "CHAT".equals(normalizedMode)) throw ApiException.forbidden("Chat 模式只允许 MySQL 只读操作，请切换到 Build 模式");
            return new Operation(command, args, write);
        }
        if ("REDIS".equals(type)) {
            if (Set.of("XSET", "STREAM_UPDATE", "XUPDATE").contains(command)) throw ApiException.badRequest("Redis Stream 已有消息不能原地修改；可由用户另行决定是否追加更正消息");
            if (!REDIS_READS.contains(command) && !REDIS_WRITES.contains(command)) throw ApiException.badRequest("不支持该 Redis 操作或无法安全分类");
            validateDatabase(type, database);
            validateRedis(command, validated);
            boolean write = REDIS_WRITES.contains(command);
            if (write && "CHAT".equals(normalizedMode)) throw ApiException.forbidden("Chat 模式只允许 Redis 只读操作，请切换到 Build 模式");
            return new Operation(command, args, write);
        }
        throw ApiException.badRequest("不支持的数据库类型");
    }

    private void validateDatabase(String type, JsonNode database) {
        if (database == null || database.isNull()) return;
        if ("MYSQL".equals(type)) identifier(database.asText(""));
        else if (!database.isIntegralNumber() || database.asInt(-1) < 0 || database.asInt() > 9999) throw ApiException.badRequest("Redis 数据库编号应为 0 到 9999");
    }

    private void validateMysql(String command, JsonNode args) {
        switch (command) {
            case "DATABASES" -> only(args);
            case "TABLES" -> only(args);
            case "DESCRIBE" -> { only(args, "table"); identifier(args.path("table").asText("")); }
            case "SELECT" -> {
                only(args, "table", "columns", "where", "limit"); identifier(args.path("table").asText(""));
                if (args.has("columns")) {
                    if (!args.get("columns").isArray() || args.get("columns").isEmpty() || args.get("columns").size() > 100) throw ApiException.badRequest("查询列必须是非空数组");
                    args.get("columns").forEach(column -> { if (!column.isTextual()) throw ApiException.badRequest("查询列无效"); if (!column.asText().equals("*")) identifier(column.asText()); });
                }
                if (args.has("where")) fields(args.get("where"), false);
                if (args.has("limit") && (!args.get("limit").canConvertToInt() || args.get("limit").asInt() < 1 || args.get("limit").asInt() > 500)) throw ApiException.badRequest("查询上限应为 1 到 500");
            }
            case "INSERT" -> validateInsert(args);
            case "UPDATE" -> { only(args, "table", "values", "where"); identifier(args.path("table").asText("")); fields(args.get("values"), true); fields(args.get("where"), true); }
            case "DELETE" -> { only(args, "table", "where"); identifier(args.path("table").asText("")); fields(args.get("where"), true); }
            default -> throw ApiException.badRequest("不支持该操作");
        }
    }

    private void validateInsert(JsonNode args) {
        identifier(args.path("table").asText(""));
        JsonNode values = args.get("values");
        if (values != null && values.isObject()) {
            only(args, "table", "values");
            fields(values, true);
            return;
        }
        only(args, "table", "columns", "values");
        JsonNode columns = args.get("columns");
        if (columns == null || !columns.isArray() || columns.isEmpty() || columns.size() > 100) {
            throw ApiException.badRequest("批量写入 columns 必须是 1 到 100 个字段名");
        }
        Set<String> unique = new HashSet<>();
        columns.forEach(column -> {
            if (!column.isTextual()) throw ApiException.badRequest("批量写入字段名无效");
            identifier(column.asText());
            if (!unique.add(column.asText())) throw ApiException.badRequest("批量写入字段不能重复");
        });
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 100) {
            throw ApiException.badRequest("批量写入 values 必须包含 1 到 100 行数据");
        }
        values.forEach(row -> {
            if (!row.isArray() || row.size() != columns.size()) throw ApiException.badRequest("批量写入每行数据必须与 columns 数量一致");
            row.forEach(this::validateScalar);
        });
    }

    private void validateScalar(JsonNode value) {
        if (!(value.isTextual() || value.isNumber() || value.isBoolean() || value.isNull())) {
            throw ApiException.badRequest("批量写入字段值类型不支持");
        }
        if (value.isTextual() && value.asText().length() > 65_536) throw ApiException.badRequest("批量写入字段值过长");
    }

    private void validateRedis(String command, JsonNode args) {
        switch (command) {
            case "SCAN" -> {
                only(args, "pattern", "limit", "cursor");
                if (args.has("pattern")) {
                    JsonNode pattern = args.get("pattern");
                    if (!pattern.isTextual() || pattern.asText().isEmpty() || pattern.asText().length() > 256) {
                        throw ApiException.badRequest("SCAN pattern 应为 1 到 256 个字符");
                    }
                }
                if (args.has("limit") && (!args.get("limit").canConvertToInt()
                        || args.get("limit").asInt() < 1 || args.get("limit").asInt() > 1000)) {
                    throw ApiException.badRequest("SCAN limit 应为 1 到 1000");
                }
                validateCursor(args);
                return;
            }
            case "DBSIZE" -> { only(args); return; }
            case "GET", "TYPE", "EXISTS", "TTL", "PTTL", "STRLEN", "HLEN", "LLEN", "SCARD", "ZCARD", "XLEN", "DEL" -> only(args, "key");
            case "HGETALL", "SMEMBERS" -> {
                only(args, "key", "cursor");
                if (args.has("cursor") && (!args.get("cursor").isTextual() || !args.get("cursor").asText().matches("[0-9]{1,20}"))) throw ApiException.badRequest("游标无效");
            }
            case "SET", "LPUSH", "RPUSH", "SADD", "SREM", "ZREM" -> { only(args, "key", "value"); text(args, "value", true); }
            case "EXPIRE" -> { only(args, "key", "seconds"); integer(args, "seconds"); }
            case "HGET", "HDEL" -> { only(args, "key", "field"); text(args, "field", true); }
            case "HSET" -> { only(args, "key", "field", "value"); text(args, "field", true); text(args, "value", true); }
            case "LRANGE" -> {
                only(args, "key", "start", "stop"); long start = integer(args, "start"), stop = integer(args, "stop");
                if (start < 0 || stop < start || stop - start > 499) throw ApiException.badRequest("列表范围应为非负且最多 500 项");
            }
            case "LSET" -> { only(args, "key", "index", "value"); integer(args, "index"); text(args, "value", true); }
            case "LREM" -> { only(args, "key", "count", "value"); integer(args, "count"); text(args, "value", true); }
            case "ZRANGE" -> {
                only(args, "key", "start", "stop"); long start = integer(args, "start"), stop = integer(args, "stop");
                if (start < 0 || stop < start || stop - start > 499) throw ApiException.badRequest("排序集合范围应为非负且最多 500 项");
            }
            case "ZADD" -> {
                only(args, "key", "value", "score"); text(args, "value", true);
                if (!args.path("score").isNumber() || !Double.isFinite(args.path("score").asDouble())) throw ApiException.badRequest("分数必须是有限数值");
            }
            case "XRANGE" -> { only(args, "key"); }
            case "XADD" -> {
                only(args, "key", "fields"); JsonNode fields = args.get("fields");
                if (fields == null || !fields.isObject() || fields.isEmpty() || fields.size() > 100) throw ApiException.badRequest("Stream 字段不能为空");
                fields.fields().forEachRemaining(entry -> { if (entry.getKey().isEmpty() || !entry.getValue().isTextual()) throw ApiException.badRequest("Stream 字段和值必须为文本"); });
            }
            case "XDEL" -> { only(args, "key", "id"); if (!text(args, "id", false).matches("[0-9]+-[0-9]+")) throw ApiException.badRequest("Stream 消息 ID 无效"); }
            default -> throw ApiException.badRequest("不支持该操作");
        }
        text(args, "key", false);
    }

    private static void validateCursor(JsonNode args) {
        if (args.has("cursor") && (!args.get("cursor").isTextual()
                || !args.get("cursor").asText().matches("[0-9]{1,20}"))) {
            throw ApiException.badRequest("游标无效");
        }
    }

    private void fields(JsonNode node, boolean required) {
        if (node == null && !required) return;
        if (node == null || !node.isObject() || (required && node.isEmpty()) || node.size() > 100) throw ApiException.badRequest("字段或条件不能为空且必须为对象");
        node.fields().forEachRemaining(entry -> {
            identifier(entry.getKey());
            if (!(entry.getValue().isTextual() || entry.getValue().isNumber() || entry.getValue().isBoolean() || entry.getValue().isNull())) throw ApiException.badRequest("字段值类型不支持");
        });
    }

    private void only(JsonNode args, String... allowed) {
        Set<String> names = new HashSet<>(java.util.List.of(allowed));
        Iterator<String> fields = args.fieldNames();
        while (fields.hasNext()) if (!names.contains(fields.next())) throw ApiException.badRequest("包含不支持的操作参数");
    }

    private static String text(JsonNode args, String key, boolean allowEmpty) {
        JsonNode node = args.get(key);
        if (node == null || !node.isTextual() || (!allowEmpty && node.asText().isEmpty()) || node.asText().length() > 65536) throw ApiException.badRequest("参数 " + key + " 无效");
        return node.asText();
    }
    private static long integer(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw ApiException.badRequest("参数 " + key + " 必须为整数");
        return node.asLong();
    }
    public static String identifier(String value) {
        if (value == null || !value.matches("[\\p{L}\\p{N}_$]{1,64}")) throw ApiException.badRequest("表名或字段名无效");
        return value;
    }
    public record OperationRequest(String connectionId, String command, JsonNode args, String runId) {}
    public record Operation(String command, JsonNode args, boolean write) {}
}
