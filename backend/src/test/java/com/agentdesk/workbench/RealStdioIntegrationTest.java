package com.agentdesk.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.mcp.McpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WORKBENCH_RUN_STDIO_TEST", matches = "1")
class RealStdioIntegrationTest {
    private static final Path TEMP = temp();
    @Autowired JdbcTemplate db;
    @Autowired McpService mcp;
    @Autowired ObjectMapper json;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("workbench.data-dir", () -> TEMP.resolve("data").toString());
        registry.add("workbench.key-file", () -> TEMP.resolve("key/master.key").toString());
    }

    @Test void realStdioRetainsSessionAndRequiresApprovalForEachCall() throws Exception {
        Path directory = Files.createDirectories(TEMP.resolve("server"));
        Path script = directory.resolve("server.py");
        Files.writeString(script, """
                import sys, json
                count = 0
                for line in sys.stdin:
                    request = json.loads(line)
                    method = request.get('method')
                    if 'id' not in request: continue
                    if method == 'initialize':
                        result = {'protocolVersion':'2025-06-18','capabilities':{},'serverInfo':{'name':'fixture','version':'1'}}
                    elif method == 'tools/list':
                        result = {'tools':[{'name':'count','description':'counts calls','inputSchema':{'type':'object'}}]}
                    elif method == 'tools/call':
                        count += 1
                        result = {'content':[{'type':'text','text':str(count)}]}
                    else: continue
                    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}), flush=True)
                """);
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('stdio','stdio@example.com','unused',?)", System.currentTimeMillis());
        db.update("INSERT INTO user_settings(user_id) VALUES('stdio')");
        var server = mcp.create("stdio", new McpService.ServerInput("STDIO 测试", "STDIO", "", "python3", List.of(script.toString()), "", true));
        assertThat(mcp.refresh("stdio", server.id())).extracting(McpService.ToolView::name).containsExactly("count");
        Principal user = new Principal("stdio", "USER", "test");
        var pending = mcp.request(user, new McpService.CallInput(server.id(), "count", json.valueToTree(Map.of()), null));
        assertThat(pending.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThatThrownBy(() -> mcp.approve(new Principal("stdio", "AGENT", "test", "run"), pending.action().id())).isInstanceOf(ApiException.class);
        var first = mcp.approve(user, pending.action().id());
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.result().path("content").path(0).path("text").asText()).isEqualTo("1");
        mcp.approve(user, pending.action().id());
        var next = mcp.request(user, new McpService.CallInput(server.id(), "count", json.valueToTree(Map.of()), null));
        assertThat(mcp.approve(user, next.action().id()).result().path("content").path(0).path("text").asText()).isEqualTo("2");
        var changed = mcp.request(user, new McpService.CallInput(server.id(), "count", json.valueToTree(Map.of()), null));
        Files.writeString(script, "\n# 已变更\n", java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> mcp.approve(user, changed.action().id())).isInstanceOf(ApiException.class);
        assertThat(mcp.getAction("stdio", changed.action().id()).status()).isEqualTo("EXPIRED");
    }
    @Test void nodeStdioHandlesServerPingAndReturnsToolResult() throws Exception {
        Path directory = Files.createDirectories(TEMP.resolve("node-server"));
        Path script = directory.resolve("server.mjs");
        Files.writeString(script, """
                import readline from 'node:readline';
                const lines = readline.createInterface({input:process.stdin});
                const send = value => process.stdout.write(JSON.stringify(value)+'\\n');
                let listId;
                lines.on('line', raw => {
                  const r = JSON.parse(raw);
                  if (r.method === 'initialize') send({jsonrpc:'2.0',id:r.id,result:{protocolVersion:'2025-06-18',capabilities:{},serverInfo:{name:'node',version:'1'}}});
                  else if (r.method === 'tools/list') { listId = r.id; send({jsonrpc:'2.0',id:'server-ping',method:'ping'}); }
                  else if (r.id === 'server-ping') send({jsonrpc:'2.0',id:listId,result:{tools:[{name:'echo',description:'echo',inputSchema:{type:'object'}}]}});
                  else if (r.method === 'tools/call') send({jsonrpc:'2.0',id:r.id,result:{content:[{type:'text',text:r.params.arguments.value}]}});
                });
                """);
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('stdio-node','stdio-node@example.com','unused',?)", System.currentTimeMillis());
        db.update("INSERT INTO user_settings(user_id) VALUES('stdio-node')");
        var server = mcp.create("stdio-node", new McpService.ServerInput("Node MCP", "STDIO", "", "node", List.of(script.toString()), "", true));
        assertThat(mcp.refresh("stdio-node", server.id())).extracting(McpService.ToolView::name).containsExactly("echo");
        Principal user = new Principal("stdio-node", "USER", "test");
        var pending = mcp.request(user, new McpService.CallInput(server.id(), "echo", json.valueToTree(Map.of("value", "node-ok")), null));
        var result = mcp.approve(user, pending.action().id());
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result().path("content").path(0).path("text").asText()).isEqualTo("node-ok");
    }
    @Test void bundledExampleProvidesRealEchoAndCalculation() throws Exception {
        Path example = Path.of("../examples/本地MCP示例/本地MCP示例.py").toAbsolutePath().normalize();
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('stdio-example','stdio-example@example.com','unused',?)", System.currentTimeMillis());
        db.update("INSERT INTO user_settings(user_id) VALUES('stdio-example')");
        var server = mcp.create("stdio-example", new McpService.ServerInput("内置示例", "STDIO", "", "python3", List.of(example.toString()), "", true));
        assertThat(mcp.refresh("stdio-example", server.id())).extracting(McpService.ToolView::name).containsExactly("echo", "sum_numbers");
        Principal user = new Principal("stdio-example", "USER", "test");
        var pending = mcp.request(user, new McpService.CallInput(server.id(), "sum_numbers", json.valueToTree(Map.of("numbers", List.of(1, 2, 3))), null));
        assertThat(mcp.approve(user, pending.action().id()).result().path("content").path(0).path("text").asText()).isEqualTo("6");
    }
    @Test void invalidStdioProtocolFailsWithoutInventingTools() throws Exception {
        Path directory = Files.createDirectories(TEMP.resolve("invalid-server"));
        Path script = directory.resolve("invalid.py");
        Files.writeString(script, "import sys\nsys.stdin.readline()\nprint('not-json', flush=True)\n");
        db.update("INSERT INTO users(id,email,password_hash,created_at) VALUES('stdio-invalid','stdio-invalid@example.com','unused',?)", System.currentTimeMillis());
        db.update("INSERT INTO user_settings(user_id) VALUES('stdio-invalid')");
        var server = mcp.create("stdio-invalid", new McpService.ServerInput("invalid", "STDIO", "", "python3", List.of(script.toString()), "", true));
        assertThatThrownBy(() -> mcp.refresh("stdio-invalid", server.id())).isInstanceOf(ApiException.class).hasMessageContaining("MCP");
        assertThat(mcp.tools("stdio-invalid", server.id())).isEmpty();
    }
    private static Path temp() { try { return Files.createTempDirectory("workbench-real-stdio-"); } catch (Exception e) { throw new IllegalStateException(e); } }
}
