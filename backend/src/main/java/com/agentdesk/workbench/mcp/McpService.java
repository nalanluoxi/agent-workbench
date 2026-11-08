package com.agentdesk.workbench.mcp;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.database.WorkflowService;
import com.agentdesk.workbench.process.RestrictedProcess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

@Service
public class McpService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SecretVault vault;
    private final ObjectMapper json;
    private final McpHttpClient client;
    private final McpStdioClient stdio;
    private final WorkflowService workflows;
    private final ApplicationEventPublisher events;
    public McpService(JdbcTemplate db, TransactionTemplate tx, SecretVault vault, ObjectMapper json, McpHttpClient client, McpStdioClient stdio,
                      WorkflowService workflows, ApplicationEventPublisher events) {
        this.db = db; this.tx = tx; this.vault = vault; this.json = json; this.client = client; this.stdio = stdio;
        this.workflows = workflows; this.events = events;
    }

    public List<ServerView> list(String userId) {
        return db.query("SELECT id,name,transport,url,command,args_json,bearer_cipher IS NOT NULL,enabled,version FROM mcp_servers WHERE user_id=? ORDER BY created_at DESC",
                (rs, n) -> new ServerView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), parseNullable(rs.getString(6)), rs.getBoolean(7), rs.getBoolean(8), rs.getInt(9)), userId);
    }
    public ServerView get(String userId, String id) { return list(userId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("MCP 服务不存在")); }
    public List<ToolView> tools(String userId, String serverId) {
        int version = get(userId, serverId).version();
        return db.query("SELECT name,description,input_schema,enabled,read_only FROM mcp_tools WHERE user_id=? AND server_id=? ORDER BY name",
                (rs, n) -> new ToolView(serverId, rs.getString(1), rs.getString(2), parse(rs.getString(3)), rs.getBoolean(4), rs.getBoolean(5), version), userId, serverId);
    }
    public List<ToolView> enabledTools(String userId) {
        return db.query("SELECT t.server_id,t.name,t.description,t.input_schema,t.enabled,t.read_only,s.version FROM mcp_tools t JOIN mcp_servers s ON s.id=t.server_id WHERE t.user_id=? AND s.user_id=? AND t.enabled=1 AND s.enabled=1 ORDER BY s.name,t.name",
                (rs, n) -> new ToolView(rs.getString(1), rs.getString(2), rs.getString(3), parse(rs.getString(4)), rs.getBoolean(5), rs.getBoolean(6), rs.getInt(7)), userId, userId);
    }
    public List<ToolView> enabledReadOnlyTools(String userId) {
        return enabledTools(userId).stream().filter(ToolView::readOnly).toList();
    }
    public ServerView create(String userId, ServerInput input) {
        Prepared item = prepare(input); String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        db.update("INSERT INTO mcp_servers(id,user_id,name,transport,url,command,args_json,bearer_cipher,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, userId, item.name(), item.transport(), item.url(), item.command(), stringify(item.args()), vault.encrypt(item.token()), item.enabled() ? 1 : 0, now, now);
        return get(userId, id);
    }
    @Transactional
    public ServerView update(String userId, String id, ServerInput input) {
        ServerView old = get(userId, id); Prepared item = prepare(input);
        if (!old.transport().equals(item.transport())) throw ApiException.badRequest("不能更改已有服务的传输类型");
        int executing = db.queryForObject("SELECT COUNT(*) FROM mcp_actions WHERE user_id=? AND server_id=? AND status='EXECUTING'", Integer.class, userId, id);
        if (executing > 0) throw ApiException.conflict("服务有正在执行的调用，暂不能修改");
        String cipher = item.token() == null || item.token().isBlank() ? null : vault.encrypt(item.token());
        db.update("UPDATE mcp_servers SET name=?,url=?,command=?,args_json=?,bundle_hash=NULL,bearer_cipher=COALESCE(?,bearer_cipher),enabled=?,version=version+1,updated_at=? WHERE user_id=? AND id=?",
                item.name(), item.url(), item.command(), stringify(item.args()), cipher, item.enabled() ? 1 : 0, System.currentTimeMillis(), userId, id);
        stdio.invalidate(id);
        return get(userId, id);
    }
    @Transactional
    public void delete(String userId, String id) {
        get(userId, id);
        int active = db.queryForObject("SELECT COUNT(*) FROM mcp_actions WHERE user_id=? AND server_id=? AND status IN ('PENDING','APPROVED','EXECUTING')", Integer.class, userId, id);
        if (active > 0) throw ApiException.conflict("该服务仍有待确认或执行中的调用");
        db.update("DELETE FROM mcp_servers WHERE user_id=? AND id=?", userId, id);
        stdio.invalidate(id);
    }
    public List<ToolView> refresh(String userId, String id) {
        ServerConfig config = resolve(userId, id);
        if (!config.enabled()) throw ApiException.badRequest("MCP 服务已停用");
        RestrictedProcess.Plan plan;
        List<McpHttpClient.Tool> discovered;
        try {
            plan = config.transport().equals("STDIO") ? stdio.prepare(config.command(), config.args()) : null;
            discovered = plan == null ? client.discover(config.url(), config.token()) : stdio.discover(id, plan);
        }
        catch (IllegalStateException e) { throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, e.getMessage() == null ? "无法连接 MCP 服务" : e.getMessage()); }
        tx.executeWithoutResult(status -> {
            if (get(userId, id).version() != config.version()) throw ApiException.conflict("服务配置已变更，请重新刷新");
            if (executing(userId, id) > 0) throw ApiException.conflict("服务有正在执行的调用，暂不能刷新");
            Set<String> names = discovered.stream().map(McpHttpClient.Tool::name).collect(java.util.stream.Collectors.toSet());
            for (ToolView old : tools(userId, id)) if (!names.contains(old.name())) db.update("DELETE FROM mcp_tools WHERE server_id=? AND user_id=? AND name=?", id, userId, old.name());
            for (McpHttpClient.Tool tool : discovered) db.update("INSERT INTO mcp_tools(server_id,user_id,name,description,input_schema,enabled) VALUES(?,?,?,?,?,1) "
                            + "ON CONFLICT(server_id,name) DO UPDATE SET description=excluded.description,input_schema=excluded.input_schema",
                    id, userId, tool.name(), tool.description(), stringify(tool.inputSchema()));
            db.update("UPDATE mcp_servers SET bundle_hash=?,version=version+1,updated_at=? WHERE user_id=? AND id=?", plan == null ? null : plan.fingerprint(), System.currentTimeMillis(), userId, id);
        });
        return tools(userId, id);
    }
    @Transactional
    public ToolView setToolEnabled(String userId, String serverId, String name, boolean enabled) {
        get(userId, serverId);
        if (executing(userId, serverId) > 0) throw ApiException.conflict("服务有正在执行的调用，暂不能更改工具状态");
        int changed = db.update("UPDATE mcp_tools SET enabled=? WHERE user_id=? AND server_id=? AND name=?", enabled ? 1 : 0, userId, serverId, name);
        if (changed == 0) throw ApiException.notFound("MCP 工具不存在");
        db.update("UPDATE mcp_servers SET version=version+1,updated_at=? WHERE user_id=? AND id=?", System.currentTimeMillis(), userId, serverId);
        return tools(userId, serverId).stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }

    @Transactional
    public ToolView setToolReadOnly(String userId, String serverId, String name, boolean readOnly) {
        get(userId, serverId);
        if (executing(userId, serverId) > 0) throw ApiException.conflict("服务有正在执行的调用，暂不能更改工具权限");
        int changed = db.update("UPDATE mcp_tools SET read_only=? WHERE user_id=? AND server_id=? AND name=?", readOnly ? 1 : 0, userId, serverId, name);
        if (changed == 0) throw ApiException.notFound("MCP 工具不存在");
        db.update("UPDATE mcp_servers SET version=version+1,updated_at=? WHERE user_id=? AND id=?", System.currentTimeMillis(), userId, serverId);
        return tools(userId, serverId).stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }

    public JsonNode callReadOnly(Principal principal, CallInput input) {
        if (!"AGENT".equals(principal.kind()) || principal.scopeRunId() == null || !principal.scopeRunId().equals(input.runId())) {
            throw ApiException.forbidden("只读 MCP 工具只能由所属 Chat 运行调用");
        }
        int valid = db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=? AND user_id=? AND status='running' AND mode='CHAT'", Integer.class, input.runId(), principal.userId());
        if (valid == 0) throw ApiException.forbidden("Chat 运行不存在或已经结束");
        ServerConfig server = validateCall(principal.userId(), input.serverId(), input.toolName(), input.args());
        int readOnly = db.queryForObject("SELECT COUNT(*) FROM mcp_tools WHERE user_id=? AND server_id=? AND name=? AND enabled=1 AND read_only=1", Integer.class,
                principal.userId(), input.serverId(), input.toolName());
        if (readOnly == 0) throw ApiException.forbidden("该 MCP 工具未明确标记为只读，请切换到 Build 模式");
        try {
            if (!bundleUnchanged(server)) throw ApiException.conflict("本地 MCP 脚本已修改，请重新发现工具");
            return server.transport().equals("HTTP") ? client.call(server.url(), server.token(), input.toolName(), input.args())
                    : stdio.call(server.id(), stdio.prepare(server.command(), server.args()), input.toolName(), input.args());
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("只读 MCP 工具调用失败：" + (e.getMessage() == null ? "服务异常" : e.getMessage()), e); }
    }

    public WorkflowService.JobView request(Principal principal, CallInput input) {
        if (input == null || input.serverId() == null || input.toolName() == null || input.args() == null || !input.args().isObject()) throw ApiException.badRequest("MCP 调用参数无效");
        if ("AGENT".equals(principal.kind()) && (principal.scopeRunId() == null || !principal.scopeRunId().equals(input.runId()))) throw ApiException.forbidden("Agent 凭据只能用于所属运行");
        if (input.runId() != null && !input.runId().isBlank()) {
            int valid = db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=? AND user_id=? AND status='running'", Integer.class, input.runId(), principal.userId());
            if (valid == 0) throw ApiException.forbidden("运行不存在或已经暂停");
        }
        ServerConfig server = validateCall(principal.userId(), input.serverId(), input.toolName(), input.args());
        Map<String,Object> operation = new java.util.LinkedHashMap<>();
        operation.put("serverId", input.serverId()); operation.put("transport", server.transport()); operation.put("toolName", input.toolName()); operation.put("args", input.args());
        if (server.transport().equals("STDIO")) { operation.put("command", server.command()); operation.put("startupArgs", server.args()); }
        String snapshot = stringify(operation);
        if (snapshot.length() > 1_000_000) throw ApiException.badRequest("MCP 调用参数过大");
        String jobId = UUID.randomUUID().toString(), actionId = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO workflow_jobs(id,user_id,run_id,connection_id,stage,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    jobId, principal.userId(), input.runId(), "mcp:" + input.serverId(), "execute", "WAITING_CONFIRMATION", now, now);
            String digest = digest(principal.userId(), jobId, actionId, server.id(), server.version(), snapshot);
            db.update("INSERT INTO mcp_actions(id,job_id,user_id,server_id,server_version,operation_json,operation_digest,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    actionId, jobId, principal.userId(), server.id(), server.version(), snapshot, digest, "PENDING", now + 600_000, now, now);
            event(jobId, "validate", "DONE", "MCP 服务和工具已校验");
            event(jobId, "execute", "WAITING_CONFIRMATION", "外部 MCP 调用等待用户确认");
        });
        return workflows.getJob(principal.userId(), jobId);
    }


    private ServerConfig validateCall(String userId, String serverId, String toolName, JsonNode args) {
        if (serverId == null || serverId.isBlank() || toolName == null || toolName.isBlank() || args == null || !args.isObject()) throw ApiException.badRequest("MCP 调用参数无效");
        ServerConfig server = resolve(userId, serverId);
        if (!server.enabled()) throw ApiException.badRequest("MCP 服务已停用");
        if (!bundleUnchanged(server)) throw ApiException.conflict("本地 MCP 脚本已修改或尚未刷新，请先重新发现工具");
        int available = db.queryForObject("SELECT COUNT(*) FROM mcp_tools WHERE user_id=? AND server_id=? AND name=? AND enabled=1", Integer.class, userId, serverId, toolName);
        if (available == 0) throw ApiException.badRequest("MCP 工具不存在或已停用，请刷新服务");
        return server;
    }

    public WorkflowService.JobView approve(Principal principal, String actionId) {
        requireUser(principal);
        Claim claim = tx.execute(status -> claim(principal.userId(), actionId));
        if (claim == null) throw ApiException.conflict("调用无法认领");
        if (claim.expired()) { events.publishEvent(new WorkflowService.JobFinished(claim.action().jobId())); throw ApiException.conflict("确认已过期或服务配置已修改"); }
        if (claim.server() != null) execute(claim);
        WorkflowService.JobView job = workflows.getJob(principal.userId(), claim.action().jobId());
        if (Set.of("COMPLETED", "FAILED", "UNKNOWN", "REJECTED", "EXPIRED").contains(job.status())) events.publishEvent(new WorkflowService.JobFinished(job.id()));
        return job;
    }
    private Claim claim(String userId, String id) {
        Action action = action(userId, id);
        if (!action.status().equals("PENDING")) return new Claim(action, null, false);
        ServerConfig server = resolve(userId, action.serverId());
        if (action.expiresAt() <= System.currentTimeMillis() || server.version() != action.version() || !server.enabled() || !bundleUnchanged(server)) {
            db.update("UPDATE mcp_actions SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING'", System.currentTimeMillis(), id);
            job(action.jobId(), "EXPIRED", null, "确认已过期或服务配置已修改"); event(action.jobId(), "execute", "EXPIRED", "确认已过期或服务配置已修改");
            return new Claim(action, null, true);
        }
        if (!vault.matches(action.digest(), digest(userId, action.jobId(), id, action.serverId(), action.version(), action.operation()))) throw ApiException.conflict("MCP 操作内容校验失败");
        JsonNode operation = parse(action.operation());
        int available = db.queryForObject("SELECT COUNT(*) FROM mcp_tools WHERE user_id=? AND server_id=? AND name=? AND enabled=1", Integer.class, userId, action.serverId(), operation.path("toolName").asText());
        if (available == 0) throw ApiException.conflict("工具已停用或移除，请重新发起");
        int changed = db.update("UPDATE mcp_actions SET status='APPROVED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING' AND expires_at>?", System.currentTimeMillis(), id, userId, System.currentTimeMillis());
        if (changed == 0) throw ApiException.conflict("确认状态已变化");
        changed = db.update("UPDATE mcp_actions SET status='EXECUTING',updated_at=? WHERE id=? AND user_id=? AND status='APPROVED'", System.currentTimeMillis(), id, userId);
        if (changed == 0) throw ApiException.conflict("调用已被其他请求认领");
        job(action.jobId(), "RUNNING", null, null); event(action.jobId(), "execute", "RUNNING", "用户已确认，正在调用 MCP 工具");
        return new Claim(action, server, false);
    }
    private void execute(Claim claim) {
        Action action = claim.action(); JsonNode operation = parse(action.operation());
        try {
            ServerConfig server = claim.server();
            if (!bundleUnchanged(server)) throw new IllegalStateException("本地 MCP 脚本已修改");
            JsonNode result = server.transport().equals("HTTP") ? client.call(server.url(), server.token(), operation.path("toolName").asText(), operation.path("args"))
                    : stdio.call(server.id(), stdio.prepare(server.command(), server.args()), operation.path("toolName").asText(), operation.path("args"));
            boolean failed = result.path("isError").asBoolean(false);
            String status = failed ? "FAILED" : "COMPLETED", actionStatus = failed ? "FAILED" : "SUCCEEDED";
            tx.executeWithoutResult(t -> {
                db.update("UPDATE mcp_actions SET status=?,updated_at=? WHERE id=? AND status='EXECUTING'", actionStatus, System.currentTimeMillis(), action.id());
                job(action.jobId(), status, stringify(result), failed ? "MCP 工具返回错误，请核查实际效果" : null);
                event(action.jobId(), "execute", status, failed ? "MCP 工具返回错误" : "MCP 工具已返回结果");
            });
        } catch (Exception e) {
            tx.executeWithoutResult(t -> {
                db.update("UPDATE mcp_actions SET status='UNKNOWN',error='MCP 调用结果未知，请核查',updated_at=? WHERE id=? AND status='EXECUTING'", System.currentTimeMillis(), action.id());
                job(action.jobId(), "UNKNOWN", null, "MCP 调用结果未知，请核查"); event(action.jobId(), "execute", "UNKNOWN", "MCP 调用结果未知，请核查");
            });
        }
    }
    public WorkflowService.JobView reject(Principal principal, String id) {
        requireUser(principal); Action action = action(principal.userId(), id);
        tx.executeWithoutResult(status -> {
            if (db.update("UPDATE mcp_actions SET status='REJECTED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING'", System.currentTimeMillis(), id, principal.userId()) == 0) throw ApiException.conflict("调用已处理");
            job(action.jobId(), "REJECTED", null, "用户拒绝执行"); event(action.jobId(), "execute", "REJECTED", "用户拒绝执行");
        });
        events.publishEvent(new WorkflowService.JobFinished(action.jobId()));
        return workflows.getJob(principal.userId(), action.jobId());
    }
    public WorkflowService.ActionView getAction(String userId, String id) {
        Action action = action(userId, id);
        return new WorkflowService.ActionView(action.id(), action.jobId(), action.serverId(), action.version(), parse(action.operation()), action.status(), action.expiresAt(), "MCP");
    }
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void expirePending() {
        List<Action> expired = db.query("SELECT id,job_id,server_id,server_version,operation_json,operation_digest,status,expires_at FROM mcp_actions WHERE status='PENDING' AND expires_at<=? LIMIT 500", (rs, n) -> row(rs), System.currentTimeMillis());
        for (Action action : expired) {
            Boolean changed = tx.execute(t -> {
                int count = db.update("UPDATE mcp_actions SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING' AND expires_at<=?", System.currentTimeMillis(), action.id(), System.currentTimeMillis());
                if (count == 0) return false;
                job(action.jobId(), "EXPIRED", null, "确认已过期"); event(action.jobId(), "execute", "EXPIRED", "确认已过期"); return true;
            });
            if (Boolean.TRUE.equals(changed)) events.publishEvent(new WorkflowService.JobFinished(action.jobId()));
        }
    }

    private ServerConfig resolve(String userId, String id) {
        List<ServerConfig> items = db.query("SELECT id,transport,url,bearer_cipher,enabled,version,command,args_json,bundle_hash FROM mcp_servers WHERE user_id=? AND id=?",
                (rs, n) -> new ServerConfig(rs.getString(1), rs.getString(2), rs.getString(3), vault.decrypt(rs.getString(4)), rs.getBoolean(5), rs.getInt(6), rs.getString(7),
                        rs.getString(8) == null ? List.of() : json.convertValue(parse(rs.getString(8)), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}), rs.getString(9)), userId, id);
        if (items.isEmpty()) throw ApiException.notFound("MCP 服务不存在");
        return items.get(0);
    }
    private boolean bundleUnchanged(ServerConfig server) {
        if (server.transport().equals("HTTP")) return true;
        try { return server.bundleHash() != null && server.bundleHash().equals(stdio.prepare(server.command(), server.args()).fingerprint()); }
        catch (Exception e) { return false; }
    }
    private int executing(String userId, String id) { return db.queryForObject("SELECT COUNT(*) FROM mcp_actions WHERE user_id=? AND server_id=? AND status='EXECUTING'", Integer.class, userId, id); }
    private Action action(String userId, String id) {
        List<Action> rows = db.query("SELECT id,job_id,server_id,server_version,operation_json,operation_digest,status,expires_at FROM mcp_actions WHERE user_id=? AND id=?", (rs, n) -> row(rs), userId, id);
        if (rows.isEmpty()) throw ApiException.notFound("MCP 确认动作不存在");
        return rows.get(0);
    }
    private Action row(java.sql.ResultSet rs) throws java.sql.SQLException { return new Action(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)); }
    private Prepared prepare(ServerInput input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 120 || input.transport() == null || !Set.of("HTTP", "STDIO").contains(input.transport())) throw ApiException.badRequest("MCP 名称或传输类型无效");
        String url = input.url() == null ? "" : input.url().trim(), command = input.command() == null ? "" : input.command().trim();
        if (input.transport().equals("HTTP")) {
            try { URI uri = URI.create(url); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw ApiException.badRequest("MCP 地址必须是有效的 HTTP 或 HTTPS 地址，不能包含凭据或查询参数"); }
        } else if (command.isBlank() || command.length() > 1024) throw ApiException.badRequest("请输入 STDIO 启动命令");
        List<String> args = input.args() == null ? List.of() : input.args();
        if (args.size() > 100 || args.stream().anyMatch(value -> value == null || value.length() > 1024)) throw ApiException.badRequest("MCP 启动参数无效");
        return new Prepared(input.name().trim(), input.transport(), url, command, args, input.token(), input.enabled() == null || input.enabled());
    }
    private void job(String id, String status, String result, String error) { db.update("UPDATE workflow_jobs SET status=?,stage=?,result_json=?,error=?,updated_at=? WHERE id=?", status, status.equals("COMPLETED") ? "summarize" : "execute", result, error, System.currentTimeMillis(), id); }
    private void event(String id, String stage, String status, String message) { db.update("INSERT INTO workflow_events(job_id,stage,status,message,created_at) VALUES(?,?,?,?,?)", id, stage, status, message, System.currentTimeMillis()); }
    private void requireUser(Principal principal) { if (!principal.canApprove()) throw ApiException.forbidden("只有已登录用户可以确认操作"); }
    private String digest(String user, String job, String id, String server, int version, String operation) { return vault.digest("mcp-action", user + "\0" + job + "\0" + id + "\0" + server + "\0" + version + "\0" + operation); }
    private String stringify(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("无法保存 MCP 数据", e); } }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException("无法读取 MCP 数据", e); } }
    private JsonNode parseNullable(String value) { return value == null ? null : parse(value); }
    private record Prepared(String name, String transport, String url, String command, List<String> args, String token, boolean enabled) {}
    private record ServerConfig(String id, String transport, String url, String token, boolean enabled, int version, String command, List<String> args, String bundleHash) {}
    private record Action(String id, String jobId, String serverId, int version, String operation, String digest, String status, long expiresAt) {}
    private record Claim(Action action, ServerConfig server, boolean expired) {}
    public record ServerInput(String name, String transport, String url, String command, List<String> args, String token, Boolean enabled) {}
    public record ServerView(String id, String name, String transport, String url, String command, JsonNode args, boolean hasToken, boolean enabled, int version) {}
    public record ToolView(String serverId, String name, String description, JsonNode inputSchema, boolean enabled, boolean readOnly, int serverVersion) {}
    public record CallInput(String serverId, String toolName, JsonNode args, String runId) {}
}
