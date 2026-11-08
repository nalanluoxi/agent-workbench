package com.agentdesk.workbench.memory;

import com.agentdesk.workbench.config.WorkbenchProperties;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Mandatory Redis infrastructure for durable memory jobs and hot recall caching. */
@Component
public class MemoryRedisStore {
    private final WorkbenchProperties properties;
    private final String prefix;
    private RedisClient client;
    private StatefulRedisConnection<String,String> connection;

    public MemoryRedisStore(WorkbenchProperties properties) {
        this.properties = properties;
        this.prefix = "agentdesk:" + sha256(properties.dataDir()).substring(0, 16) + ":memory:";
    }

    @PostConstruct
    public void connect() {
        RedisURI.Builder builder = RedisURI.Builder.redis(properties.memoryRedisHost(), properties.memoryRedisPort())
                .withDatabase(properties.memoryRedisDatabase()).withTimeout(Duration.ofSeconds(5));
        if (properties.memoryRedisPassword() != null && !properties.memoryRedisPassword().isBlank()) {
            builder.withPassword(properties.memoryRedisPassword().toCharArray());
        }
        try {
            client = RedisClient.create(builder.build());
            connection = client.connect();
            if (!"PONG".equalsIgnoreCase(connection.sync().ping())) throw new IllegalStateException("Redis PING 未返回 PONG");
        } catch (Exception e) {
            close();
            throw new IllegalStateException("记忆系统强制依赖 Redis，但无法连接 " + properties.memoryRedisHost() + ":" + properties.memoryRedisPort(), e);
        }
    }

    public void enqueue(String runId) { connection.sync().rpush(prefix + "jobs", runId); }

    public String take(int timeoutSeconds) {
        KeyValue<String,String> item = connection.sync().blpop(timeoutSeconds, prefix + "jobs");
        return item == null || !item.hasValue() ? null : item.getValue();
    }

    public long version(String userId) {
        String value = connection.sync().get(prefix + "version:" + userId);
        return value == null ? 0 : Long.parseLong(value);
    }

    public void invalidate(String userId) { connection.sync().incr(prefix + "version:" + userId); }

    public String cachedRecall(String userId, String query) {
        return connection.sync().get(recallKey(userId, query, version(userId)));
    }

    public void cacheRecall(String userId, String query, String json) {
        connection.sync().setex(recallKey(userId, query, version(userId)), 120, json);
    }

    private String recallKey(String userId, String query, long version) {
        return prefix + "recall:" + userId + ":" + version + ":" + sha256(query);
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法生成记忆缓存键", e); }
    }

    @PreDestroy
    public void close() {
        if (connection != null) { connection.close(); connection = null; }
        if (client != null) { client.shutdown(); client = null; }
    }
}
