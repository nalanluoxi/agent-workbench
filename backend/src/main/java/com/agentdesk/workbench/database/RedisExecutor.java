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
import java.util.LinkedHashMap;
import java.util.Map;
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
            case "GET" -> result(redis.get(key));
            case "TTL" -> result(redis.ttl(key));
            case "SET" -> result(redis.set(key, value));
            case "DEL" -> result(redis.del(key));
            case "EXPIRE" -> result(redis.expire(key, args.path("seconds").asLong()));
            case "HGET" -> result(redis.hget(key, field));
            case "HSET" -> result(redis.hset(key, field, value));
            case "HDEL" -> result(redis.hdel(key, field));
            case "HGETALL" -> { var page = redis.hscan(key, ScanCursor.of(args.path("cursor").asText("0")), new ScanArgs().limit(100)); yield Map.of("items", page.getMap(), "cursor", page.getCursor(), "finished", page.isFinished()); }
            case "LRANGE" -> result(redis.lrange(key, args.path("start").asLong(), args.path("stop").asLong()));
            case "LPUSH" -> result(redis.lpush(key, value));
            case "RPUSH" -> result(redis.rpush(key, value));
            case "LSET" -> result(redis.lset(key, args.path("index").asLong(), value));
            case "LREM" -> result(redis.lrem(key, args.path("count").asLong(), value));
            case "SMEMBERS" -> { var page = redis.sscan(key, ScanCursor.of(args.path("cursor").asText("0")), new ScanArgs().limit(100)); yield Map.of("items", page.getValues(), "cursor", page.getCursor(), "finished", page.isFinished()); }
            case "SADD" -> result(redis.sadd(key, value));
            case "SREM" -> result(redis.srem(key, value));
            case "ZRANGE" -> result(redis.zrangeWithScores(key, args.path("start").asLong(), args.path("stop").asLong()).stream().map(v -> Map.of("value", v.getValue(), "score", v.getScore())).toList());
            case "ZADD" -> result(redis.zadd(key, args.path("score").asDouble(), value));
            case "ZREM" -> result(redis.zrem(key, value));
            case "XRANGE" -> result(redis.xrange(key, Range.unbounded(), Limit.create(0, 100)).stream().map(m -> Map.of("id", m.getId(), "fields", m.getBody())).toList());
            case "XADD" -> { Map<String, String> fields = new LinkedHashMap<>(); args.path("fields").fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText())); yield result(redis.xadd(key, fields)); }
            case "XDEL" -> result(redis.xdel(key, args.path("id").asText()));
            default -> throw new IllegalArgumentException("unsupported operation");
        };
    }

    private static Map<String, Object> result(Object value) { Map<String, Object> result = new LinkedHashMap<>(); result.put("value", value); return result; }
}
