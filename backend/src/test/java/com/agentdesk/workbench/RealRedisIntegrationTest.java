package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.database.ConnectionService;
import com.agentdesk.workbench.database.OperationPolicy;
import com.agentdesk.workbench.database.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_REDIS_TEST", matches = "1")
class RealRedisIntegrationTest {
    private static final Path TEMP = temp();
    private static Process server;
    private static int port;
    @Autowired JdbcTemplate db;
    @Autowired ConnectionService connections;
    @Autowired WorkflowService workflows;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeAll static void startRedis() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        server = new ProcessBuilder("redis-server", "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no", "--dir", TEMP.toString())
                .redirectErrorStream(true).redirectOutput(TEMP.resolve("redis.log").toFile()).start();
        for (int i = 0; i < 100; i++) {
            if (!server.isAlive()) throw new IllegalStateException("临时 Redis 启动失败");
            try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) { return; }
            catch (java.io.IOException e) { Thread.sleep(30); }
        }
        throw new IllegalStateException("临时 Redis 未就绪");
    }
    @AfterAll static void stopRedis() throws Exception {
        if (server != null) { server.destroy(); if (!server.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) server.destroyForcibly(); }
    }

    @Test void sixDataTypesExecuteOnlyAfterApproval() {
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('redis-user','redis@example.com','unused',?)", now);
        db.update("INSERT INTO user_settings(user_id) VALUES('redis-user')");
        var connection = connections.create("redis-user", new ConnectionService.ConnectionInput("REDIS", "临时测试", "127.0.0.1", port, "0", "", ""));
        Principal user = new Principal("redis-user", "USER", "test");
        var first = create(user, connection.id(), "SET", Map.of("key", "string", "value", "one"));
        assertThat(first.status()).isEqualTo("WAITING_CONFIRMATION");
        try (var client = RedisClient.create("redis://127.0.0.1:" + port); var redis = client.connect()) {
            assertThat(redis.sync().get("string")).isNull();
            assertThat(workflows.approve(user, first.action().id()).status()).isEqualTo("COMPLETED");
            assertThat(redis.sync().get("string")).isEqualTo("one");
            write(user, connection.id(), "HSET", Map.of("key", "hash", "field", "f", "value", "v"));
            write(user, connection.id(), "LPUSH", Map.of("key", "list", "value", "v"));
            write(user, connection.id(), "SADD", Map.of("key", "set", "value", "v"));
            write(user, connection.id(), "ZADD", Map.of("key", "zset", "value", "v", "score", 1));
            write(user, connection.id(), "XADD", Map.of("key", "stream", "fields", Map.of("event", "created")));
            assertThat(redis.sync().hget("hash", "f")).isEqualTo("v");
            assertThat(redis.sync().lrange("list", 0, 0)).containsExactly("v");
            assertThat(redis.sync().smembers("set")).contains("v");
            assertThat(redis.sync().zscore("zset", "v")).isEqualTo(1.0);
            assertThat(redis.sync().xlen("stream")).isEqualTo(1);
            assertThat(create(user, connection.id(), "GET", Map.of("key", "string")).status()).isEqualTo("COMPLETED");
            assertThatThrownBy(() -> create(user, connection.id(), "XSET", Map.of("key", "stream")))
                    .isInstanceOf(ApiException.class).hasMessageContaining("不能原地修改");
        }
    }
    private void write(Principal user, String connection, String command, Map<String, Object> args) {
        var pending = create(user, connection, command, args);
        assertThat(pending.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(workflows.approve(user, pending.action().id()).status()).isEqualTo("COMPLETED");
    }
    private WorkflowService.JobView create(Principal user, String id, String command, Map<String, Object> args) {
        return workflows.create(user, new OperationPolicy.OperationRequest(id, command, json.valueToTree(args), null));
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-real-redis-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
