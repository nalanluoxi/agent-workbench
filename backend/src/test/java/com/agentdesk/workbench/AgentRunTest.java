package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentdesk.workbench.agent.AgentToolRunner;
import com.agentdesk.workbench.agent.ModelGateway;
import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.database.ConnectionService;
import com.agentdesk.workbench.database.MysqlExecutor;
import com.agentdesk.workbench.database.OperationPolicy;
import com.agentdesk.workbench.database.WorkflowService;
import com.agentdesk.workbench.models.ModelService;
import com.agentdesk.workbench.memory.MemoryGateway;
import com.agentdesk.workbench.mcp.McpService;
import com.agentdesk.workbench.mcp.McpHttpClient;
import com.agentdesk.workbench.sessions.SessionService;
import com.agentdesk.workbench.skills.SkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
class AgentRunTest {
    private static final Path TEMP = temp();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired SecretVault vault;
    @Autowired ModelService models;
    @Autowired SessionService sessions;
    @Autowired ConnectionService connections;
    @Autowired WorkflowService workflows;
    @Autowired McpService mcp;
    @Autowired SkillService skills;
    @MockBean ModelGateway gateway;
    @MockBean AgentToolRunner runner;
    @MockBean MysqlExecutor mysql;
    @MockBean MemoryGateway memoryGateway;
    @MockBean McpHttpClient mcpHttp;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key").resolve("master.key").toString());
    }
    @BeforeEach void setup() {
        long now = System.currentTimeMillis();
        db.update("INSERT OR IGNORE INTO users(id,email,password_hash,created_at) VALUES('u','agent@example.com','unused',?)", now);
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES('u')");
        db.update("INSERT OR REPLACE INTO auth_tokens(token_hash,user_id,kind,expires_at,created_at) VALUES(?,'u','USER',?,?)", vault.digest("auth-token", "user-token"), now + 120_000, now);
        when(memoryGateway.extract(anyString(), anyString(), any(), anyString())).thenReturn(new MemoryGateway.Extracted(List.of(), List.of(), List.of()));
    }

    @Test void streamsModelTextAndStoresTheSelectedModelVersion() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "聊天");
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> {
            Consumer<String> output = invocation.getArgument(7); output.accept("你好"); output.accept("，用户");
            return new ModelGateway.ModelReply("你好，用户", List.of());
        });
        String run = start(session.id(), model.id());
        waitFor(run, "completed");
        assertThat(db.queryForObject("SELECT model_version FROM runs WHERE id=?", Integer.class, run)).isEqualTo(1);
        assertThat(sessions.messages("u", session.id())).extracting(SessionService.MessageView::content).contains("你好，用户");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM run_events WHERE run_id=? AND type='text_delta'", Integer.class, run)).isEqualTo(2);
        mvc.perform(get("/api/runs/" + run + "/events")).andExpect(status().isUnauthorized());
    }

    @Test void databaseRouteIsRecommendationInsteadOfHardToolGate() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "路由候选");
        AtomicInteger turns = new AtomicInteger();
        String args = json.writeValueAsString(Map.of("toolId", ConnectionService.REDIS_TOOL, "command", "GET", "args", Map.of("key", "x")));
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("", List.of(new ModelGateway.ToolCall("wrong-tool", "database_operation", args)))
                : new ModelGateway.ModelReply("已按实际工具结果处理", List.of()));
        when(runner.execute(anyString(), anyString(), any())).thenReturn(json.valueToTree(Map.of("status", "COMPLETED", "result", Map.of("value", "ok"))));
        String run = start(session.id(), model.id(), "CHAT", "请查询 MySQL 数据库");
        waitFor(run, "completed");
        verify(runner, times(1)).execute(anyString(), eq(run), any());
        assertThat(db.queryForObject("SELECT payload FROM run_events WHERE run_id=? AND type='tool_result' ORDER BY seq DESC LIMIT 1", String.class, run))
                .contains("COMPLETED");
    }

    @Test void redisKeyDiscoveryUsesScanToolAndReturnsRealResult() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "Redis Key 扫描");
        connections.create("u", new ConnectionService.ConnectionInput("REDIS", "local", "localhost", 6379, "0", "", ""));
        String args = json.writeValueAsString(Map.of("toolId", ConnectionService.REDIS_TOOL, "command", "SCAN",
                "args", Map.of("database", 0, "pattern", "*", "limit", 1000)));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> {
            assertThat(invocation.<JsonNode>getArgument(3).get(0).path("content").asText())
                    .contains("必须调用 SCAN").contains("不得使用或建议 KEYS *");
            return turns.getAndIncrement() == 0
                    ? new ModelGateway.ModelReply("", List.of(new ModelGateway.ToolCall("scan-1", "database_operation", args)))
                    : new ModelGateway.ModelReply("已返回真实 Key 列表", List.of());
        });
        when(runner.execute(anyString(), anyString(), any())).thenReturn(json.valueToTree(Map.of("status", "COMPLETED", "result",
                Map.of("items", List.of("one", "two"), "count", 2, "cursor", "0", "finished", true, "truncated", false, "pattern", "*", "limit", 1000))));

        String run = start(session.id(), model.id(), "CHAT", "查看 Redis 有什么 Key");
        waitFor(run, "completed");

        assertThat(db.queryForObject("SELECT messages_json FROM run_contexts WHERE run_id=?", String.class, run))
                .contains("SCAN");
        verify(runner, times(1)).execute(anyString(), eq(run), any());
        assertThat(sessions.messages("u", session.id())).extracting(SessionService.MessageView::content)
                .contains("已返回真实 Key 列表");
    }

    @Test void databaseIntentRejectsTextOnlyAnswerAndRetriesWithTool() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "真实查询证据");
        connections.create("u", new ConnectionService.ConnectionInput("MYSQL", "demo", "localhost", 3306, "crawler", "", ""));
        String args = json.writeValueAsString(Map.of("toolId", ConnectionService.MYSQL_TOOL, "command", "SELECT",
                "args", Map.of("database", "crawler", "table", "crawl_site_config", "columns", List.of("*"), "limit", 10)));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> {
            int turn = turns.getAndIncrement();
            Consumer<String> output = invocation.getArgument(7);
            if (turn == 0) {
                output.accept("这是没有依据的假结果");
                return new ModelGateway.ModelReply("这是没有依据的假结果", List.of());
            }
            if (turn == 1) return new ModelGateway.ModelReply("", List.of(new ModelGateway.ToolCall("query-1", "database_operation", args)));
            output.accept("已返回真实查询结果");
            return new ModelGateway.ModelReply("已返回真实查询结果", List.of());
        });
        when(runner.execute(anyString(), anyString(), any())).thenReturn(json.valueToTree(Map.of("status", "COMPLETED", "result", List.of(Map.of("id", 1)))));

        String run = start(session.id(), model.id(), "CHAT", "重新查询 crawler.crawl_site_config 前十行数据");
        waitFor(run, "completed");

        assertThat(turns.get()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM run_events WHERE run_id=? AND type='policy_retry'", Integer.class, run)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM run_events WHERE run_id=? AND type='tool_result'", Integer.class, run)).isEqualTo(1);
        assertThat(sessions.messages("u", session.id())).extracting(SessionService.MessageView::content)
                .contains("已返回真实查询结果")
                .doesNotContain("这是没有依据的假结果");
        assertThat(sessions.artifacts("u", session.id())).hasSize(1);
        assertThat(sessions.artifacts("u", session.id()).get(0).payload().path("result").get(0).path("id").asInt()).isEqualTo(1);
    }

    @Test void createTableGeneratesPersistentConfirmationCardBeforeExecution() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "建表确认");
        connections.create("u", new ConnectionService.ConnectionInput("MYSQL", "demo", "localhost", 3306, "crawler", "", ""));
        String sql = "CREATE TABLE crawler.test_crawler (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)";
        String args = json.writeValueAsString(Map.of("toolId", ConnectionService.MYSQL_TOOL, "command", "SQL", "args", Map.of("sql", sql)));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("", List.of(new ModelGateway.ToolCall("create-table", "database_operation", args)))
                : new ModelGateway.ModelReply("操作已拒绝", List.of()));
        when(runner.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String user = invocation.getArgument(0), run = invocation.getArgument(1);
            JsonNode input = json.readTree(((ModelGateway.ToolCall) invocation.getArgument(2)).arguments());
            return json.valueToTree(workflows.create(new Principal(user, "AGENT", "test", run),
                    new OperationPolicy.OperationRequest(input.path("toolId").asText(), input.path("command").asText(), input.path("args"), run)));
        });

        String run = start(session.id(), model.id(), "BUILD", "请在 crawler 中创建 test_crawler 表");
        waitFor(run, "waiting_confirmation");

        verify(mysql, never()).execute(any(), any());
        var pendingArtifacts = sessions.artifacts("u", session.id());
        assertThat(pendingArtifacts).hasSize(1);
        assertThat(pendingArtifacts.get(0).payload().path("action").path("status").asText()).isEqualTo("PENDING");
        assertThat(pendingArtifacts.get(0).payload().path("action").path("operation").path("args").path("risk").asText()).isEqualTo("DANGER");

        String action = db.queryForObject("SELECT a.id FROM db_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, run);
        mvc.perform(post("/api/actions/" + action + "/reject").header("Authorization", "Bearer user-token")).andExpect(status().isOk());
        waitFor(run, "completed");

        verify(mysql, never()).execute(any(), any());
        assertThat(sessions.artifacts("u", session.id())).hasSize(1);
        assertThat(sessions.artifacts("u", session.id()).get(0).payload().path("action").path("status").asText()).isEqualTo("REJECTED");
    }

    @Test void createTableFieldReplyKeepsDatabaseIntentAndGeneratesCard() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "建表多轮追问");
        connections.create("u", new ConnectionService.ConnectionInput("MYSQL", "demo", "localhost", 3306, "crawler", "", ""));
        sessions.append("u", session.id(), "user", "在 crawler 写一个表 test_crawler", "BUILD");
        sessions.append("u", session.id(), "assistant", "请提供字段，建表会生成确认卡片", "BUILD");
        String sql = "CREATE TABLE crawler.test_crawler (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), created DATETIME DEFAULT CURRENT_TIMESTAMP)";
        String args = json.writeValueAsString(Map.of("toolId", ConnectionService.MYSQL_TOOL, "command", "SQL", "args", Map.of("sql", sql)));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("", List.of(new ModelGateway.ToolCall("create-fields", "database_operation", args)))
                : new ModelGateway.ModelReply("操作已拒绝", List.of()));
        when(runner.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String user = invocation.getArgument(0), run = invocation.getArgument(1);
            JsonNode input = json.readTree(((ModelGateway.ToolCall) invocation.getArgument(2)).arguments());
            return json.valueToTree(workflows.create(new Principal(user, "AGENT", "test", run),
                    new OperationPolicy.OperationRequest(input.path("toolId").asText(), input.path("command").asText(), input.path("args"), run)));
        });

        String run = start(session.id(), model.id(), "BUILD", "id,name,created");
        waitFor(run, "waiting_confirmation");

        assertThat(db.queryForObject("SELECT intent FROM route_decisions WHERE run_id=?", String.class, run)).isEqualTo("MYSQL_MUTATE");
        assertThat(sessions.artifacts("u", session.id())).hasSize(1);
        assertThat(sessions.artifacts("u", session.id()).get(0).payload().path("action").path("status").asText()).isEqualTo("PENDING");
        verify(mysql, never()).execute(any(), any());

        String action = db.queryForObject("SELECT a.id FROM db_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, run);
        mvc.perform(post("/api/actions/" + action + "/reject").header("Authorization", "Bearer user-token")).andExpect(status().isOk());
        waitFor(run, "completed");
    }

    @Test void agentWaitsForHumanThenResumesWithRealWorkflowStatus() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "写入");
        var connection = connections.create("u", new ConnectionService.ConnectionInput("MYSQL", "demo", "localhost", 3306, "demo", "", ""));
        String args = json.writeValueAsString(Map.of("toolId", ConnectionService.MYSQL_TOOL, "command", "UPDATE", "args", Map.of("table", "orders", "where", Map.of("id", 1), "values", Map.of("status", "done"))));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("需要您的确认", List.of(new ModelGateway.ToolCall("call-1", "database_operation", args)))
                : new ModelGateway.ModelReply("已完成", List.of()));
        when(runner.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String user = invocation.getArgument(0), run = invocation.getArgument(1);
            ModelGateway.ToolCall call = invocation.getArgument(2);
            JsonNode input = json.readTree(call.arguments());
            var request = new OperationPolicy.OperationRequest(input.get("toolId").asText(), input.get("command").asText(), input.get("args"), run);
            return json.valueToTree(workflows.create(new Principal(user, "AGENT", "test", run), request));
        });
        when(mysql.execute(any(), any())).thenReturn(Map.of("affectedRows", 1));
        String run = start(session.id(), model.id(), "BUILD", "请修改 MySQL 数据库");
        waitFor(run, "waiting_confirmation");
        verify(mysql, never()).execute(any(), any());
        String action = db.queryForObject("SELECT a.id FROM db_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, run);
        mvc.perform(post("/api/actions/" + action + "/approve").header("Authorization", "Bearer user-token")).andExpect(status().isOk());
        waitFor(run, "completed");
        verify(mysql, times(1)).execute(any(), any());
        assertThat(turns.get()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM run_events WHERE run_id=? AND type='confirmation_required'", Integer.class, run)).isEqualTo(1);
    }

    @Test void mcpToolCallWaitsForUserAndThenResumes() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "MCP");
        var server = mcp.create("u", new McpService.ServerInput("服务", "HTTP", "http://127.0.0.1:9999/mcp", "", List.of(), "", true));
        db.update("INSERT INTO mcp_tools(server_id,user_id,name,description,input_schema,enabled) VALUES(?,'u','echo','回显','{\"type\":\"object\"}',1)", server.id());
        String args = json.writeValueAsString(Map.of("serverId", server.id(), "toolName", "echo", "args", Map.of("value", "test")));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("需要确认", List.of(new ModelGateway.ToolCall("mcp-call", "mcp_operation", args)))
                : new ModelGateway.ModelReply("已完成", List.of()));
        when(mcpHttp.call(anyString(), any(), anyString(), any())).thenReturn(json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "ok")))));
        String run = start(session.id(), model.id(), "BUILD", "请调用 MCP 外部工具");
        waitFor(run, "waiting_confirmation");
        verifyNoInteractions(mcpHttp);
        String action = db.queryForObject("SELECT a.id FROM mcp_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, run);
        mvc.perform(post("/api/mcp-actions/" + action + "/approve").header("Authorization", "Bearer user-token")).andExpect(status().isOk());
        waitFor(run, "completed");
        verify(mcpHttp, times(1)).call(anyString(), any(), eq("echo"), any());
    }

    @Test void chatCanCallExplicitlyReadOnlyMcpToolWithoutConfirmation() throws Exception {
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "只读 MCP");
        var server = mcp.create("u", new McpService.ServerInput("服务", "HTTP", "http://127.0.0.1:9999/mcp", "", List.of(), "", true));
        db.update("INSERT INTO mcp_tools(server_id,user_id,name,description,input_schema,enabled,read_only) VALUES(?,'u','lookup','只读查询','{\"type\":\"object\"}',1,1)", server.id());
        String args = json.writeValueAsString(Map.of("serverId", server.id(), "toolName", "lookup", "args", Map.of("value", "test")));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("", List.of(new ModelGateway.ToolCall("mcp-read", "mcp_operation", args)))
                : new ModelGateway.ModelReply("查询完成", List.of()));
        when(mcpHttp.call(anyString(), any(), eq("lookup"), any())).thenReturn(json.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", "ok")))));
        String run = start(session.id(), model.id(), "CHAT", "请使用 lookup 查询");
        waitFor(run, "completed");
        verify(mcpHttp, times(1)).call(anyString(), any(), eq("lookup"), any());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM run_events WHERE run_id=? AND type='confirmation_required'", Integer.class, run)).isZero();
    }

    @Test void skillScriptRequestPausesForUserAndResumesAfterRejection() throws Exception {
        Path source = Files.createDirectories(TEMP.resolve("agent-skill"));
        Files.writeString(source.resolve("SKILL.md"), "---\nname: agent-script\ndescription: test\n---\n说明\n");
        Files.writeString(source.resolve("hello.py"), "print('hello')\n");
        var skill = skills.installLocal("u", source.toString());
        var model = models.create("u", new ModelService.ModelInput("CHAT", "test", "compatible", "http://127.0.0.1:9090/v1", "demo", "secret", true));
        var session = sessions.create("u", "技能");
        String args = json.writeValueAsString(Map.of("skillId", skill.view().id(), "script", "hello.py", "args", List.of()));
        AtomicInteger turns = new AtomicInteger();
        when(gateway.complete(anyString(), anyString(), any(), any(), anyString(), anySet(), anySet(), any())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                ? new ModelGateway.ModelReply("等待确认", List.of(new ModelGateway.ToolCall("script-call", "skill_script", args)))
                : new ModelGateway.ModelReply("已拒绝", List.of()));
        String run = start(session.id(), model.id(), "BUILD", "请运行技能脚本");
        waitFor(run, "waiting_confirmation");
        String action = db.queryForObject("SELECT a.id FROM skill_script_actions a JOIN workflow_jobs j ON j.id=a.job_id WHERE j.run_id=?", String.class, run);
        mvc.perform(post("/api/skill-script-actions/" + action + "/reject").header("Authorization", "Bearer user-token")).andExpect(status().isOk());
        waitFor(run, "completed");
        assertThat(db.queryForObject("SELECT status FROM skill_script_actions WHERE id=?", String.class, action)).isEqualTo("REJECTED");
        assertThat(turns.get()).isEqualTo(2);
    }

    private String start(String sessionId, String modelId) throws Exception {
        return start(sessionId, modelId, "CHAT");
    }
    private String start(String sessionId, String modelId, String mode) throws Exception {
        return start(sessionId, modelId, mode, "请处理");
    }
    private String start(String sessionId, String modelId, String mode, String message) throws Exception {
        String response = mvc.perform(post("/api/sessions/" + sessionId + "/runs").header("Authorization", "Bearer user-token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("message", message, "modelId", modelId, "mode", mode)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }
    private void waitFor(String runId, String expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            String state = db.queryForObject("SELECT status FROM runs WHERE id=?", String.class, runId);
            if (expected.equals(state)) return;
            if (List.of("failed", "interrupted").contains(state)) throw new AssertionError("运行意外结束: " + state + " " + db.queryForObject("SELECT error FROM runs WHERE id=?", String.class, runId));
            Thread.sleep(50);
        }
        throw new AssertionError("运行未进入预期状态: " + expected);
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-agent-test-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
