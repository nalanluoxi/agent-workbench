package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.database.ConnectionService;
import com.agentdesk.workbench.database.OperationPolicy;
import com.agentdesk.workbench.database.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_MYSQL_TEST", matches = "1")
class RealMysqlIntegrationTest {
    private static final Path TEMP = temp();
    private static Process server;
    private static int port;
    @Autowired JdbcTemplate db;
    @Autowired ConnectionService connections;
    @Autowired WorkflowService workflows;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("platform").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeAll static void startMysql() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        Path data = TEMP.resolve("mysql-data"), socket = TEMP.resolve("mysql.sock"), log = TEMP.resolve("mysql.log");
        Files.createDirectories(data);
        Process init = new ProcessBuilder("mysqld", "--no-defaults", "--initialize-insecure", "--datadir=" + data, "--log-error=" + log)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!init.waitFor(60, TimeUnit.SECONDS) || init.exitValue() != 0) throw new IllegalStateException("临时 MySQL 初始化失败：" + (Files.exists(log) ? Files.readString(log) : "无日志"));
        server = new ProcessBuilder("mysqld", "--no-defaults", "--datadir=" + data, "--socket=" + socket, "--port=" + port,
                "--bind-address=127.0.0.1", "--pid-file=" + TEMP.resolve("mysql.pid"), "--log-error=" + log,
                "--skip-log-bin", "--mysqlx=OFF").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        for (int i = 0; i < 200; i++) {
            if (!server.isAlive()) throw new IllegalStateException("临时 MySQL 启动失败：" + Files.readString(log));
            try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/?connectTimeout=500", "root", "")) {
                try (var sql = connection.createStatement()) { sql.execute("CREATE DATABASE workbench_test"); sql.execute("CREATE TABLE workbench_test.orders (id INT PRIMARY KEY, status VARCHAR(64))"); }
                return;
            } catch (java.sql.SQLException e) { Thread.sleep(100); }
        }
        throw new IllegalStateException("临时 MySQL 未就绪：" + Files.readString(log));
    }
    @AfterAll static void stopMysql() throws Exception { if (server != null) { server.destroy(); if (!server.waitFor(10, TimeUnit.SECONDS)) server.destroyForcibly(); } }

    @Test void realMysqlWritesRequireApprovalAndReadsReturnActualRows() throws Exception {
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('mysql-user','mysql@example.com','unused',?)", System.currentTimeMillis());
        db.update("INSERT INTO user_settings(user_id) VALUES('mysql-user')");
        var connection = connections.create("mysql-user", new ConnectionService.ConnectionInput("MYSQL", "临时测试", "127.0.0.1", port, "workbench_test", "root", ""));
        Principal user = new Principal("mysql-user", "USER", "test");
        var pending = create(user, connection.id(), "INSERT", Map.of("table", "orders", "values", Map.of("id", 1, "status", "pending")));
        assertThat(pending.status()).isEqualTo("WAITING_CONFIRMATION");
        try (var sql = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/workbench_test", "root", ""); var query = sql.createStatement()) {
            try (var rows = query.executeQuery("SELECT COUNT(*) FROM orders")) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
            assertThat(workflows.approve(user, pending.action().id()).status()).isEqualTo("COMPLETED");
            var read = create(user, connection.id(), "SELECT", Map.of("table", "orders", "where", Map.of("id", 1)));
            assertThat(read.status()).isEqualTo("COMPLETED");
            assertThat(read.result().get(0).get("status").asText()).isEqualTo("pending");
            var update = create(user, connection.id(), "UPDATE", Map.of("table", "orders", "where", Map.of("id", 1), "values", Map.of("status", "done")));
            assertThat(workflows.approve(user, update.action().id()).status()).isEqualTo("COMPLETED");
            var delete = create(user, connection.id(), "DELETE", Map.of("table", "orders", "where", Map.of("id", 1)));
            assertThat(workflows.reject(user, delete.action().id()).status()).isEqualTo("REJECTED");
            try (var rows = query.executeQuery("SELECT status FROM orders WHERE id=1")) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("done"); }
        }
    }
    private WorkflowService.JobView create(Principal user, String id, String command, Map<String, Object> args) {
        return workflows.create(user, new OperationPolicy.OperationRequest(id, command, json.valueToTree(args), null));
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-real-mysql-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
