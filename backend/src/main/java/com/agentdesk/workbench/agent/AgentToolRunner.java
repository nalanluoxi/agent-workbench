package com.agentdesk.workbench.agent;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.database.ConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AgentToolRunner {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final ConnectionService connections;
    private final SecretVault vault;
    private final ObjectMapper mapper;
    private final WorkbenchProperties properties;
    private final Environment environment;
    private final AgentToolSandbox sandbox;
    private final SecureRandom random = new SecureRandom();

    public AgentToolRunner(JdbcTemplate db, TransactionTemplate transaction, ConnectionService connections, SecretVault vault,
                           ObjectMapper mapper, WorkbenchProperties properties, Environment environment, AgentToolSandbox sandbox) {
        this.db = db; this.transaction = transaction; this.connections = connections; this.vault = vault;
        this.mapper = mapper; this.properties = properties; this.environment = environment; this.sandbox = sandbox;
    }

    public JsonNode execute(String userId, String runId, ModelGateway.ToolCall call) {
        if (!"database_operation".equals(call.name())) throw ApiException.badRequest("模型请求了不支持的工具");
        String token = null;
        try {
            JsonNode args = mapper.readTree(call.arguments());
            String toolId = args.path("toolId").asText(args.path("connectionId").asText()), command = args.path("command").asText();
            if (toolId.isBlank() || command.isBlank() || !args.path("args").isObject()) throw ApiException.badRequest("数据库工具参数不完整");
            ConnectionService.ConnectionView connection = connections.get(userId, toolId);
            Path script = Path.of(properties.cliDir(), "bin", connection.type().equals("MYSQL") ? "agentdesk-mysql.mjs" : "agentdesk-redis.mjs").toAbsolutePath().normalize();
            if (!Files.isRegularFile(script)) throw new IllegalStateException("数据库专属 CLI 未安装，请检查本机配置");
            token = issueDelegation(userId, runId);
            int port = environment.getProperty("local.server.port", Integer.class, environment.getProperty("server.port", Integer.class, 8765));
            ProcessBuilder builder = sandbox.process(script, port);
            String path = System.getenv("PATH");
            builder.environment().clear();
            if (path != null) builder.environment().put("PATH", path);
            builder.environment().put("WORKBENCH_URL", "http://127.0.0.1:" + port);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            try (var input = process.getOutputStream()) {
                input.write(mapper.writeValueAsBytes(Map.of("token", token, "runId", runId, "toolId", toolId,
                        "command", command, "args", args.path("args"))));
            }
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try { return process.getInputStream().readNBytes(4 * 1024 * 1024 + 1); }
                catch (Exception e) { throw new IllegalStateException("无法读取工具结果", e); }
            });
            if (!process.waitFor(40, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("数据库 CLI 超时，请检查任务状态"); }
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (bytes.length > 4 * 1024 * 1024) throw new IllegalStateException("数据库工具结果过大");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("数据库 CLI 执行失败：" + text.strip());
            return mapper.readTree(text);
        } catch (ApiException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("工具调用已中断", e); }
        catch (Exception e) { throw new IllegalStateException(e.getMessage() == null ? "数据库工具调用失败" : e.getMessage(), e); }
        finally { if (token != null) db.update("DELETE FROM auth_tokens WHERE token_hash=?", vault.digest("auth-token", token)); }
    }

    private String issueDelegation(String userId, String runId) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = vault.digest("auth-token", token);
        long now = System.currentTimeMillis();
        transaction.executeWithoutResult(status -> {
            db.update("INSERT INTO auth_tokens(token_hash,user_id,kind,expires_at,created_at) VALUES(?,?,?,?,?)", hash, userId, "AGENT", now + 120_000, now);
            db.update("INSERT INTO agent_delegations(token_hash,run_id,created_at) VALUES(?,?,?)", hash, runId, now);
        });
        return token;
    }
}
