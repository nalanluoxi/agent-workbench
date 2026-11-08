package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.database.ExecutionFailure;
import com.agentdesk.workbench.database.ConnectionService;
import com.agentdesk.workbench.database.MysqlExecutor;
import com.agentdesk.workbench.database.RedisExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowTest {
    private static final Path TEMP = temporaryDirectory();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired SecretVault vault;
    @Autowired ConnectionService connections;
    @MockBean MysqlExecutor mysql;
    @MockBean RedisExecutor redis;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeEach void setup() {
        addUser("owner"); addUser("other"); addToken("owner", "agent", "AGENT");
        long now = System.currentTimeMillis();
        db.update("INSERT OR IGNORE INTO sessions(id,user_id,title,created_at,updated_at) VALUES('agent-session','owner','test',?,?)", now, now);
        db.update("INSERT OR IGNORE INTO runs(id,user_id,session_id,status,mode,created_at,updated_at) VALUES('agent-run','owner','agent-session','running','BUILD',?,?)", now, now);
        db.update("INSERT OR REPLACE INTO agent_delegations(token_hash,run_id,created_at) VALUES(?,?,?)", vault.digest("auth-token", "agent-token"), "agent-run", now);
    }

    @Test void writeRequiresOwnerAndCannotBeReplayed() throws Exception {
        String connection = connection();
        JsonNode job = create(connection, "UPDATE", Map.of("table", "orders", "where", Map.of("id", 1), "values", Map.of("status", "done")), "agent");
        String jobId = job.get("id").asText(), actionId = job.get("action").get("id").asText();
        assertThat(job.get("status").asText()).isEqualTo("WAITING_CONFIRMATION");
        verifyNoInteractions(mysql);
        mvc.perform(post("/api/actions/" + actionId + "/approve").header("Authorization", bearer("agent"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/actions/" + actionId + "/approve").header("Authorization", bearer("other"))).andExpect(status().isNotFound());
        when(mysql.execute(any(), any())).thenReturn(Map.of("affectedRows", 1));
        JsonNode done = read(mvc.perform(post("/api/actions/" + actionId + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("action").get("status").asText()).isEqualTo("SUCCEEDED");
        mvc.perform(post("/api/actions/" + actionId + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isOk());
        verify(mysql, times(1)).execute(any(), any());
        mvc.perform(get("/api/workflow-jobs/" + jobId).header("Authorization", bearer("other"))).andExpect(status().isNotFound());
    }

    @Test void readsRunDirectlyAndConnectionChangesInvalidateApproval() throws Exception {
        String connection = connection();
        when(mysql.execute(any(), any())).thenReturn(Map.of("rows", 1));
        JsonNode readJob = create(connection, "SELECT", Map.of("table", "orders", "where", Map.of("id", 1)), "owner");
        assertThat(readJob.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(readJob.get("action").isNull()).isTrue();
        JsonNode pending = create(connection, "DELETE", Map.of("table", "orders", "where", Map.of("id", 1)), "owner");
        String action = pending.get("action").get("id").asText();
        db.update("UPDATE db_connections SET version=version+1 WHERE id=?", connection);
        mvc.perform(post("/api/actions/" + action + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT status FROM db_actions WHERE id=?", String.class, action)).isEqualTo("EXPIRED");
        verify(mysql, times(1)).execute(any(), any());
    }

    @Test void unknownWriteResultCannotBeAutomaticallyRepeated() throws Exception {
        String connection = connection();
        JsonNode pending = create(connection, "INSERT", Map.of("table", "orders", "values", Map.of("id", 2)), "owner");
        String action = pending.get("action").get("id").asText();
        when(mysql.execute(any(), any())).thenThrow(new ExecutionFailure("执行结果未知，请核查", true, null));
        JsonNode result = read(mvc.perform(post("/api/actions/" + action + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("status").asText()).isEqualTo("UNKNOWN");
        mvc.perform(post("/api/actions/" + action + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isOk());
        verify(mysql, times(1)).execute(any(), any());
    }

    @Test void simultaneousApprovalsClaimTheWriteOnlyOnce() throws Exception {
        String connection = connection();
        JsonNode pending = create(connection, "INSERT", Map.of("table", "orders", "values", Map.of("id", 9)), "owner");
        String action = pending.get("action").get("id").asText();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(mysql.execute(any(), any())).thenAnswer(invocation -> { started.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return Map.of("affectedRows", 1); });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> mvc.perform(post("/api/actions/" + action + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isOk()));
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> mvc.perform(post("/api/actions/" + action + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isOk()));
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(mysql, times(1)).execute(any(), any());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void workflowDefinitionIsVisiblePerOwnerAndSnapshotCannotBeAltered() throws Exception {
        String connection = connection();
        JsonNode pending = create(connection, "INSERT", Map.of("table", "orders", "values", Map.of("id", 19)), "owner");
        String jobId = pending.get("id").asText(), actionId = pending.get("action").get("id").asText();
        JsonNode flow = read(mvc.perform(get("/api/workflow-jobs/" + jobId + "/definition").header("Authorization", bearer("owner")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(flow.path("name").asText()).isEqualTo("database-operation");
        assertThat(flow.path("stages")).hasSize(4);
        mvc.perform(get("/api/workflow-jobs/" + jobId + "/definition").header("Authorization", bearer("other"))).andExpect(status().isNotFound());
        String saved = db.queryForObject("SELECT workflow_snapshot FROM workflow_jobs WHERE id=?", String.class, jobId);
        db.update("UPDATE workflow_jobs SET workflow_snapshot=? WHERE id=?", saved.replace("理解请求", "更改后的名称"), jobId);
        mvc.perform(post("/api/actions/" + actionId + "/approve").header("Authorization", bearer("owner"))).andExpect(status().isConflict());
        verifyNoInteractions(mysql);
    }

    @Test void unboundedDeleteAndStreamModificationAreRejectedBeforeExecution() throws Exception {
        String connection = connection();
        mvc.perform(post("/api/database/operations").header("Authorization", bearer("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("connectionId", connection, "command", "DELETE", "args", Map.of("table", "orders")))))
                .andExpect(status().isBadRequest());
        String redisId = createConnection("REDIS", "0");
        String message = mvc.perform(post("/api/database/operations").header("Authorization", bearer("owner")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("connectionId", redisId, "command", "XSET", "args", Map.of("key", "stream")))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(message).contains("不能原地修改");
        verifyNoInteractions(mysql, redis);
    }

    private String connection() throws Exception { return createConnection("MYSQL", "demo"); }
    private String createConnection(String type, String database) throws Exception {
        return connections.create("owner", new ConnectionService.ConnectionInput(type, "test", "127.0.0.1", type.equals("MYSQL") ? 3306 : 6379, database, "", "")).id();
    }
    private JsonNode create(String id, String command, Map<String, Object> args, String token) throws Exception {
        return read(mvc.perform(post("/api/database/operations").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("connectionId", id, "command", command, "args", args, "runId", token.equals("agent") ? "agent-run" : "")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode read(String value) throws Exception { return json.readTree(value); }
    private void addUser(String id) {
        long now = System.currentTimeMillis();
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)", id, id + "@example.com", "unused", now);
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES(?)", id);
        addToken(id, id, "USER");
    }
    private void addToken(String id, String token, String kind) {
        long now = System.currentTimeMillis();
        db.update("INSERT OR REPLACE INTO auth_tokens(token_hash,user_id,kind,expires_at,created_at) VALUES(?,?,?,?,?)", vault.digest("auth-token", token + "-token"), id, kind, now + 60_000, now);
    }
    private static String bearer(String token) { return "Bearer " + token + "-token"; }
    private static Path temporaryDirectory() { try { return Files.createTempDirectory("workbench-flow-test-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
