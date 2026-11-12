package com.agentdesk.workbench.database;

import com.fasterxml.jackson.databind.JsonNode;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RedisExecutor {
    public Object execute(ConnectionService.ConnectionConfig config, OperationPolicy.Operation operation) {
        RedisURI.Builder uri = RedisURI.Builder.redis(config.host(), config.port()).withDatabase(Integer.parseInt(config.database())).withTimeout(Duration.ofSeconds(5));
        if (config.password() != null && !config.password().isEmpty()) {
            if (config.username() == null || config.username().isBlank()) uri.withPassword(config.password());
            else uri.withAuthentication(config.username(), config.password());
        }
        RedisClient client = RedisClient.create(uri.build());
        boolean connected = false;
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            connected = true;
            return executeCommand(connection.sync(), operation);
        } catch (Exception e) {
            boolean unknown = operation.write() && connected && (e instanceof io.lettuce.core.RedisConnectionException || e instanceof io.lettuce.core.RedisCommandTimeoutException);
            throw new ExecutionFailure(unknown ? "执行结果未知，请核查 Redis" : "Redis 操作失败，请检查连接、数据类型和权限", unknown, e);
        } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(1)); }
    }

    private Object executeCommand(RedisCommands<String, String> redis, OperationPolicy.Operation operation) {
        JsonNode args = operation.args();
        String key = args.path("key").asText();
        String value = args.path("value").asText();
        String field = args.path("field").asText();
        return switch (operation.command()) {
            case "SCAN" -> scan(redis, args);
            case "TYPE" -> result(redis.type(key));
            case "EXISTS" -> result(redis.exists(key) > 0);
            case "DBSIZE" -> result(redis.dbsize());
            case "GET" -> result(redis.get(key));
            case "TTL" -> result(redis.ttl(key));
            case "PTTL" -> expiry(redis.pttl(key));
            case "STRLEN" -> result(redis.strlen(key));
            case "SET" -> result(redis.set(key, value));
            case "DEL" -> result(redis.del(key));
            case "EXPIRE" -> result(redis.expire(key, args.path("seconds").asLong()));
            case "HGET" -> result(redis.hget(key, field));
            case "HSET" -> result(redis.hset(key, field, value));
            case "HDEL" -> result(redis.hdel(key, field));
            case "HGETALL" -> { var page = redis.hscan(key, ScanCursor.of(args.path("cursor").asText("0")), new ScanArgs().limit(100)); yield Map.of("items", page.getMap(), "cursor", page.getCursor(), "finished", page.isFinished()); }
            case "HLEN" -> result(redis.hlen(key));
            case "LRANGE" -> result(redis.lrange(key, args.path("start").asLong(), args.path("stop").asLong()));
            case "LLEN" -> result(redis.llen(key));
            case "LPUSH" -> result(redis.lpush(key, value));
            case "RPUSH" -> result(redis.rpush(key, value));
            case "LSET" -> result(redis.lset(key, args.path("index").asLong(), value));
            case "LREM" -> result(redis.lrem(key, args.path("count").asLong(), value));
            case "SMEMBERS" -> { var page = redis.sscan(key, ScanCursor.of(args.path("cursor").asText("0")), new ScanArgs().limit(100)); yield Map.of("items", page.getValues(), "cursor", page.getCursor(), "finished", page.isFinished()); }
            case "SCARD" -> result(redis.scard(key));
            case "SADD" -> result(redis.sadd(key, value));
            case "SREM" -> result(redis.srem(key, value));
            case "ZRANGE" -> result(redis.zrangeWithScores(key, args.path("start").asLong(), args.path("stop").asLong()).stream().map(v -> Map.of("value", v.getValue(), "score", v.getScore())).toList());
            case "ZCARD" -> result(redis.zcard(key));
            case "ZADD" -> result(redis.zadd(key, args.path("score").asDouble(), value));
            case "ZREM" -> result(redis.zrem(key, value));
            case "XRANGE" -> result(redis.xrange(key, Range.unbounded(), Limit.create(0, 100)).stream().map(m -> Map.of("id", m.getId(), "fields", m.getBody())).toList());
            case "XLEN" -> result(redis.xlen(key));
            case "XADD" -> { Map<String, String> fields = new LinkedHashMap<>(); args.path("fields").fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText())); yield result(redis.xadd(key, fields)); }
            case "XDEL" -> result(redis.xdel(key, args.path("id").asText()));
            default -> throw new IllegalArgumentException("unsupported operation");
        };
    }

    private Map<String, Object> scan(RedisCommands<String, String> redis, JsonNode args) {
        String pattern = args.path("pattern").asText("*");
        int limit = args.path("limit").asInt(1000);
        String cursor = args.path("cursor").asText("0");
        Set<String> keys = new LinkedHashSet<>();
        boolean finished = false;
        boolean truncated = false;
        int iterations = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            var page = redis.scan(ScanCursor.of(cursor), new ScanArgs().match(pattern).limit(Math.min(100, limit)));
            cursor = page.getCursor();
            for (String item : page.getKeys()) {
                if (keys.size() >= limit) { truncated = true; break; }
                keys.add(item);
            }
            finished = page.isFinished();
            if (keys.size() >= limit && !finished) truncated = true;
            iterations++;
            if (!finished && (iterations >= 10_000 || System.nanoTime() >= deadline)) truncated = true;
        } while (!finished && !truncated);
        return Map.of(
                "items", List.copyOf(keys),
                "count", keys.size(),
                "cursor", cursor,
                "finished", finished && !truncated,
                "truncated", truncated,
                "pattern", pattern,
                "limit", limit);
    }

    private Map<String, Object> expiry(long ttlMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (ttlMs == -2) {
            result.put("status", "MISSING");
            result.put("ttlMs", -2L);
        } else if (ttlMs == -1) {
            result.put("status", "PERSISTENT");
            result.put("ttlMs", -1L);
        } else {
            long expiresAt = System.currentTimeMillis() + ttlMs;
            result.put("status", "EXPIRING");
            result.put("ttlMs", ttlMs);
            result.put("ttlSeconds", Math.ceil(ttlMs / 1000.0));
            result.put("expiresAtEpochMs", expiresAt);
            result.put("expiresAt", Instant.ofEpochMilli(expiresAt).toString());
        }
        return result;
    }

    private static Map<String, Object> result(Object value) { Map<String, Object> result = new LinkedHashMap<>(); result.put("value", value); return result; }
}
