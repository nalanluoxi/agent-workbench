package com.agentdesk.workbench.database;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowService {
    private static final long APPROVAL_TTL = 10 * 60_000L;
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final ConnectionService connections;
    private final OperationPolicy policy;
    private final MysqlExecutor mysql;
    private final RedisExecutor redis;
    private final SecretVault vault;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;
    private final WorkflowDefinition definition;

    public WorkflowService(JdbcTemplate db, TransactionTemplate transaction, ConnectionService connections, OperationPolicy policy,
                           MysqlExecutor mysql, RedisExecutor redis, SecretVault vault, ObjectMapper mapper, ApplicationEventPublisher events, WorkflowDefinition definition) {
        this.db = db; this.transaction = transaction; this.connections = connections; this.policy = policy;
        this.mysql = mysql; this.redis = redis; this.vault = vault; this.mapper = mapper; this.events = events; this.definition = definition;
    }

    public JobView create(Principal principal, OperationPolicy.OperationRequest request) {
        if (request == null || request.connectionId() == null || request.connectionId().isBlank()) throw ApiException.badRequest("请选择 builtin-mysql 或 builtin-redis 工具");
        String mode = resolveMode(principal, request.runId());
        String type = request.connectionId().startsWith("builtin-") ? connections.typeFor(request.connectionId()) : connections.get(principal.userId(), request.connectionId()).type();
        OperationPolicy.Operation operation = policy.prepare(type, request, mode);
        String database = operation.args().path("database").isMissingNode() ? null : operation.args().path("database").asText();
        ConnectionService.ConnectionConfig config = connections.resolveForOperation(principal.userId(), request.connectionId(), database);
        WorkflowDefinition.Definition flow = definition.current();
        definition.requireExecutionAllowed(flow, operation.write());
        String flowSnapshot = definition.snapshot(flow);
        if ("AGENT".equals(principal.kind()) && (principal.scopeRunId() == null || !principal.scopeRunId().equals(request.runId()))) {
            throw ApiException.forbidden("Agent 凭据只能用于所属运行");
        }
        if ("AGENT".equals(principal.kind())) {
            int active = db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=? AND user_id=? AND status='running'", Integer.class, principal.scopeRunId(), principal.userId());
            if (active == 0) throw ApiException.forbidden("运行已结束或暂停，不能发起工具操作");
        }
        if (request.runId() != null && !request.runId().isBlank()) {
            int owned = db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=? AND user_id=?", Integer.class, request.runId(), principal.userId());
            if (owned == 0) throw ApiException.notFound("运行不存在");
        }
        String jobId = UUID.randomUUID().toString();
        String actionId = operation.write() ? UUID.randomUUID().toString() : null;
        com.fasterxml.jackson.databind.node.ObjectNode snapshotNode = mapper.createObjectNode();
        snapshotNode.put("command", operation.command());
        snapshotNode.set("args", operation.args());
        if (operation.write() && config.type().equals("MYSQL")) snapshotNode.set("impact", mapper.valueToTree(mysql.previewImpact(config, operation)));
        String snapshot = json(snapshotNode);
        long now = System.currentTimeMillis();
        transaction.executeWithoutResult(status -> {
            db.update("INSERT INTO workflow_jobs(id,user_id,run_id,connection_id,workflow_version,workflow_snapshot,stage,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    jobId, principal.userId(), request.runId(), config.id(), flow.version(), flowSnapshot, "execute", operation.write() ? "WAITING_CONFIRMATION" : "RUNNING", now, now);
            event(jobId, "understand", "DONE", "操作参数已接收");
            event(jobId, "validate", "DONE", "连接归属与操作类型已校验");
            if (operation.write()) {
                String digest = digest(principal.userId(), jobId, actionId, config.id(), config.version(), snapshot, flowSnapshot);
                db.update("INSERT INTO db_actions(id,job_id,user_id,connection_id,connection_version,operation_json,operation_digest,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        actionId, jobId, principal.userId(), config.id(), config.version(), snapshot, digest, "PENDING", now + APPROVAL_TTL, now, now);
                event(jobId, "execute", "WAITING_CONFIRMATION", "等待用户确认写操作");
            } else event(jobId, "execute", "RUNNING", "正在执行只读操作");
        });
        if (!operation.write()) execute(jobId, null, config, operation);
        return getJob(principal.userId(), jobId);
    }

    public JobView approve(Principal principal, String actionId) {
        requireUser(principal);
        Claim claim = transaction.execute(status -> claim(principal.userId(), actionId));
        if (claim == null) throw ApiException.conflict("操作无法认领");
        if (claim.expired()) { events.publishEvent(new JobFinished(claim.action().jobId())); throw ApiException.conflict("确认已过期或连接已修改，请重新发起操作"); }
        if (claim.operation() != null) execute(claim.action().jobId(), claim.action().id(), claim.config(), claim.operation());
        JobView job = getJob(principal.userId(), claim.action().jobId());
        if (List.of("COMPLETED", "FAILED", "UNKNOWN", "REJECTED", "EXPIRED").contains(job.status())) events.publishEvent(new JobFinished(job.id()));
        return job;
    }

    private Claim claim(String userId, String actionId) {
        ActionRow action = action(userId, actionId);
        if (!action.status().equals("PENDING")) return new Claim(action, null, null, false);
        JsonNode snapshot = parse(action.operationJson());
        String requestedDatabase = snapshot.path("args").path("database").isMissingNode()
                ? null : snapshot.path("args").path("database").asText();
        ConnectionService.ConnectionConfig config = connections.resolveForOperation(userId, action.connectionId(), requestedDatabase);
        if (action.expiresAt() <= System.currentTimeMillis() || config.version() != action.connectionVersion()) {
            db.update("UPDATE db_actions SET status='EXPIRED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING'", System.currentTimeMillis(), actionId, userId);
            updateJob(action.jobId(), "execute", "EXPIRED", null, "确认已过期或连接已修改");
            event(action.jobId(), "execute", "EXPIRED", "确认已过期或连接已修改");
            return new Claim(action, null, null, true);
        }
        String flowSnapshot = flowSnapshot(userId, action.jobId());
        definition.requireExecutionAllowed(definition.fromSnapshot(flowSnapshot), true);
        String expected = digest(userId, action.jobId(), action.id(), action.connectionId(), action.connectionVersion(), action.operationJson(), flowSnapshot);
        if (!vault.matches(expected, action.operationDigest())) throw ApiException.conflict("操作内容校验失败，请重新发起");
        OperationPolicy.Operation operation = policy.prepare(config.type(), new OperationPolicy.OperationRequest(config.id(), snapshot.path("command").asText(), snapshot.get("args"), null), jobMode(action.jobId()));
        if (!operation.write()) throw ApiException.conflict("待确认动作类型无效");
        int changed = db.update("UPDATE db_actions SET status='APPROVED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING' AND expires_at>?",
                System.currentTimeMillis(), actionId, userId, System.currentTimeMillis());
        if (changed == 0) throw ApiException.conflict("确认状态已变化，请刷新");
        changed = db.update("UPDATE db_actions SET status='EXECUTING',updated_at=? WHERE id=? AND user_id=? AND status='APPROVED'", System.currentTimeMillis(), actionId, userId);
        if (changed == 0) throw ApiException.conflict("操作已被其他请求认领");
        updateJob(action.jobId(), "execute", "RUNNING", null, null);
        event(action.jobId(), "execute", "RUNNING", "用户已确认，正在执行");
        return new Claim(action, config, operation, false);
    }

    public JobView reject(Principal principal, String actionId) {
        requireUser(principal);
        ActionRow action = action(principal.userId(), actionId);
        transaction.executeWithoutResult(status -> {
            int count = db.update("UPDATE db_actions SET status='REJECTED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING'", System.currentTimeMillis(), actionId, principal.userId());
            if (count == 0) throw ApiException.conflict("操作已处理，请刷新状态");
            updateJob(action.jobId(), "execute", "REJECTED", null, "用户拒绝执行");
            event(action.jobId(), "execute", "REJECTED", "用户拒绝执行");
        });
        events.publishEvent(new JobFinished(action.jobId()));
        return getJob(principal.userId(), action.jobId());
    }

    public JobView getJob(String userId, String id) {
        List<JobRow> rows = db.query("SELECT id,stage,status,result_json,error,created_at,updated_at FROM workflow_jobs WHERE user_id=? AND id=?",
                (rs, n) -> new JobRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getLong(7)), userId, id);
        if (rows.isEmpty()) throw ApiException.notFound("任务不存在");
        JobRow row = rows.get(0);
        return new JobView(row.id(), row.stage(), row.status(), parseNullable(row.result()), row.error(), row.createdAt(), row.updatedAt(), actionViewForJob(userId, id));
    }

    public JobView getJob(Principal principal, String id) {
        if ("AGENT".equals(principal.kind())) {
            int owned = db.queryForObject("SELECT COUNT(*) FROM workflow_jobs WHERE id=? AND user_id=? AND run_id=?", Integer.class, id, principal.userId(), principal.scopeRunId());
            if (owned == 0) throw ApiException.notFound("任务不存在");
        }
        return getJob(principal.userId(), id);
    }

    public WorkflowDefinition.Definition getDefinition(Principal principal, String id) {
        getJob(principal, id);
        return definition.fromSnapshot(flowSnapshot(principal.userId(), id));
    }

    private String flowSnapshot(String userId, String jobId) {
        List<String> snapshots = db.query("SELECT workflow_snapshot FROM workflow_jobs WHERE user_id=? AND id=?", (rs, n) -> rs.getString(1), userId, jobId);
        if (snapshots.isEmpty()) throw ApiException.notFound("任务不存在");
        return snapshots.get(0);
    }

    public List<EventView> events(String userId, String jobId, long after) {
        getJob(userId, jobId);
        return db.query("SELECT id,stage,status,message,created_at FROM workflow_events WHERE job_id=? AND id>? ORDER BY id LIMIT 500",
                (rs, n) -> new EventView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)), jobId, after);
    }

    public ActionView getAction(String userId, String id) { return view(action(userId, id)); }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void expirePending() {
        List<ExpiredAction> expired = db.query("SELECT id,job_id FROM db_actions WHERE status='PENDING' AND expires_at<=? LIMIT 500",
                (rs, n) -> new ExpiredAction(rs.getString(1), rs.getString(2)), System.currentTimeMillis());
        for (ExpiredAction item : expired) {
            Boolean changed = transaction.execute(tx -> {
                int count = db.update("UPDATE db_actions SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING' AND expires_at<=?", System.currentTimeMillis(), item.id(), System.currentTimeMillis());
                if (count == 0) return false;
                updateJob(item.jobId(), "execute", "EXPIRED", null, "确认已过期");
                event(item.jobId(), "execute", "EXPIRED", "确认已过期");
                return true;
            });
            if (Boolean.TRUE.equals(changed)) events.publishEvent(new JobFinished(item.jobId()));
        }
    }

    private ActionView actionViewForJob(String userId, String jobId) {
        List<ActionRow> rows = db.query("SELECT id,job_id,connection_id,connection_version,operation_json,operation_digest,status,expires_at FROM db_actions WHERE user_id=? AND job_id=?",
                (rs, n) -> new ActionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)), userId, jobId);
        if (!rows.isEmpty()) return view(rows.get(0));
        List<ActionView> mcp = db.query("SELECT id,job_id,server_id,server_version,operation_json,status,expires_at FROM mcp_actions WHERE user_id=? AND job_id=?",
                (rs, n) -> new ActionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), parse(rs.getString(5)), rs.getString(6), rs.getLong(7), "MCP"), userId, jobId);
        if (!mcp.isEmpty()) return mcp.get(0);
        List<ActionView> scripts = db.query("SELECT id,job_id,skill_id,skill_version,operation_json,status,expires_at FROM skill_script_actions WHERE user_id=? AND job_id=?",
                (rs, n) -> new ActionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), parse(rs.getString(5)), rs.getString(6), rs.getLong(7), "SKILL_SCRIPT"), userId, jobId);
        return scripts.isEmpty() ? null : scripts.get(0);
    }

    private ActionRow action(String userId, String id) {
        List<ActionRow> rows = db.query("SELECT id,job_id,connection_id,connection_version,operation_json,operation_digest,status,expires_at FROM db_actions WHERE user_id=? AND id=?",
                (rs, n) -> new ActionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)), userId, id);
        if (rows.isEmpty()) throw ApiException.notFound("确认动作不存在");
        return rows.get(0);
    }

    private ActionView view(ActionRow row) { return new ActionView(row.id(), row.jobId(), row.connectionId(), row.connectionVersion(), parse(row.operationJson()), row.status(), row.expiresAt(), "DATABASE"); }

    private void execute(String jobId, String actionId, ConnectionService.ConnectionConfig config, OperationPolicy.Operation operation) {
        try {
            Object result = config.type().equals("MYSQL") ? mysql.execute(config, operation) : redis.execute(config, operation);
            transaction.executeWithoutResult(status -> {
                if (actionId != null) db.update("UPDATE db_actions SET status='SUCCEEDED',updated_at=? WHERE id=? AND status='EXECUTING'", System.currentTimeMillis(), actionId);
                updateJob(jobId, "summarize", "COMPLETED", json(result), null);
                event(jobId, "execute", "DONE", "数据库返回执行结果");
                event(jobId, "summarize", "DONE", "已保存真实结果");
            });
        } catch (ExecutionFailure e) {
            String state = e.unknown() ? "UNKNOWN" : "FAILED";
            transaction.executeWithoutResult(status -> {
                if (actionId != null) db.update("UPDATE db_actions SET status=?,error=?,updated_at=? WHERE id=? AND status='EXECUTING'", state, e.getMessage(), System.currentTimeMillis(), actionId);
                updateJob(jobId, "execute", state, null, e.getMessage());
                event(jobId, "execute", state, e.getMessage());
            });
        }
    }

    private void updateJob(String id, String stage, String status, String result, String error) {
        db.update("UPDATE workflow_jobs SET stage=?,status=?,result_json=?,error=?,updated_at=? WHERE id=?", stage, status, result, error, System.currentTimeMillis(), id);
    }
    private void event(String jobId, String stage, String status, String message) {
        db.update("INSERT INTO workflow_events(job_id,stage,status,message,created_at) VALUES(?,?,?,?,?)", jobId, stage, status, message, System.currentTimeMillis());
    }
    private String digest(String userId, String jobId, String actionId, String connectionId, int version, String snapshot, String flowSnapshot) {
        return vault.digest("database-action", userId + "\0" + jobId + "\0" + actionId + "\0" + connectionId + "\0" + version + "\0" + snapshot + "\0" + flowSnapshot);
    }
    private String resolveMode(Principal principal, String runId) {
        if (!"AGENT".equals(principal.kind())) return "BUILD";
        if (runId == null || runId.isBlank() || !runId.equals(principal.scopeRunId())) throw ApiException.forbidden("Agent 凭据只能用于所属运行");
        String mode = db.query("SELECT mode FROM runs WHERE id=? AND user_id=?", rs -> rs.next() ? rs.getString(1) : null, runId, principal.userId());
        if (mode == null) throw ApiException.forbidden("运行不存在，不能发起工具操作");
        return mode;
    }
    private String jobMode(String jobId) {
        String mode = db.query("SELECT COALESCE(r.mode,'BUILD') FROM workflow_jobs j LEFT JOIN runs r ON r.id=j.run_id WHERE j.id=?",
                rs -> rs.next() ? rs.getString(1) : null, jobId);
        return mode == null ? "BUILD" : mode;
    }
    private static void requireUser(Principal principal) { if (!principal.canApprove()) throw ApiException.forbidden("只有已登录用户可以确认操作"); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("无法保存结果", e); } }
    private JsonNode parse(String value) { try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException("无法读取记录", e); } }
    private JsonNode parseNullable(String value) { return value == null ? null : parse(value); }

    private record ActionRow(String id, String jobId, String connectionId, int connectionVersion, String operationJson, String operationDigest, String status, long expiresAt) {}
    private record ExpiredAction(String id, String jobId) {}
    private record JobRow(String id, String stage, String status, String result, String error, long createdAt, long updatedAt) {}
    private record Claim(ActionRow action, ConnectionService.ConnectionConfig config, OperationPolicy.Operation operation, boolean expired) {}
    public record ActionView(String id, String jobId, String connectionId, int connectionVersion, JsonNode operation, String status, long expiresAt, String kind) {}
    public record JobView(String id, String stage, String status, JsonNode result, String error, long createdAt, long updatedAt, ActionView action) {}
    public record EventView(long id, String stage, String status, String message, long createdAt) {}
    public record JobFinished(String jobId) {}
}
