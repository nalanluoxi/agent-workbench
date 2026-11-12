package com.agentdesk.workbench.memory;

import com.agentdesk.workbench.config.WorkbenchProperties;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Mandatory Redis infrastructure for durable memory jobs and hot recall caching. */
@Component
public class MemoryRedisStore {
    private final WorkbenchProperties properties;
    private final String prefix;
    private RedisClient client;
    private StatefulRedisConnection<String,String> connection;
    private StatefulRedisConnection<String,String> workerConnection;
    private static final String GROUP = "memory-workers";
    private static final String CONSUMER = "worker-1";

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
            workerConnection = client.connect();
            if (!"PONG".equalsIgnoreCase(connection.sync().ping())) throw new IllegalStateException("Redis PING 未返回 PONG");
            ensureStreamGroup();
        } catch (Exception e) {
            close();
            throw new IllegalStateException("记忆系统强制依赖 Redis，但无法连接 " + properties.memoryRedisHost() + ":" + properties.memoryRedisPort(), e);
        }
    }

    public void enqueue(String runId) {
        connection.sync().xadd(streamKey(), Map.of("runId", runId, "queuedAt", Long.toString(System.currentTimeMillis())));
    }

    public StreamJob take(int timeoutSeconds) {
        List<StreamMessage<String,String>> messages = workerConnection.sync().xreadgroup(
                Consumer.from(GROUP, CONSUMER), XReadArgs.Builder.block(Duration.ofSeconds(timeoutSeconds)).count(1),
                XReadArgs.StreamOffset.lastConsumed(streamKey()));
        if (messages == null || messages.isEmpty()) return null;
        StreamMessage<String,String> message = messages.get(0);
        return new StreamJob(message.getId(), message.getBody().get("runId"));
    }

    public void acknowledge(StreamJob job) { connection.sync().xack(streamKey(), GROUP, job.messageId()); }

    public long pendingJobs() { return connection.sync().xpending(streamKey(), GROUP).getCount(); }

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

    public String cachedContext(String userId, String sessionId) {
        return connection.sync().get(prefix + "context:" + userId + ":" + sessionId + ":" + version(userId));
    }

    public void cacheContext(String userId, String sessionId, String json) {
        connection.sync().setex(prefix + "context:" + userId + ":" + sessionId + ":" + version(userId), 300, json);
    }

    public String cachedSessionWindow(String userId, String sessionId) {
        return connection.sync().get(prefix + "session-window:" + userId + ":" + sessionId + ":" + version(userId));
    }

    public void cacheSessionWindow(String userId, String sessionId, String json) {
        connection.sync().setex(prefix + "session-window:" + userId + ":" + sessionId + ":" + version(userId), 300, json);
    }

    private String streamKey() { return prefix + "capture:stream"; }

    private void ensureStreamGroup() {
        try {
            connection.sync().xgroupCreate(XReadArgs.StreamOffset.from(streamKey(), "0-0"), GROUP, XGroupCreateArgs.Builder.mkstream());
        } catch (RuntimeException e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) throw e;
        }
    }

    public record StreamJob(String messageId, String runId) {}

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法生成记忆缓存键", e); }
    }

    @PreDestroy
    public void close() {
        if (workerConnection != null) { workerConnection.close(); workerConnection = null; }
        if (connection != null) { connection.close(); connection = null; }
        if (client != null) { client.shutdown(); client = null; }
    }
}
