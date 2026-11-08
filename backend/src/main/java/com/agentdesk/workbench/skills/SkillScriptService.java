package com.agentdesk.workbench.skills;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.database.WorkflowService;
import com.agentdesk.workbench.process.RestrictedProcess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SkillScriptService {
    private static final int MAX_OUTPUT = 256 * 1024;
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final SkillService skills;
    private final RestrictedProcess sandbox;
    private final WorkflowService workflows;
    private final SecretVault vault;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public SkillScriptService(JdbcTemplate db, TransactionTemplate transaction, SkillService skills, RestrictedProcess sandbox,
                              WorkflowService workflows, SecretVault vault, ObjectMapper json, ApplicationEventPublisher events) {
        this.db = db; this.transaction = transaction; this.skills = skills; this.sandbox = sandbox; this.workflows = workflows;
        this.vault = vault; this.json = json; this.events = events;
    }

    public WorkflowService.JobView request(Principal principal, Input input) {
        if (input == null || input.skillId() == null || input.script() == null) throw ApiException.badRequest("请选择技能和脚本");
        if ("AGENT".equals(principal.kind()) && (principal.scopeRunId() == null || !principal.scopeRunId().equals(input.runId()))) throw ApiException.forbidden("Agent 凭据只能用于所属运行");
        if (input.runId() != null && !input.runId().isBlank()) {
            int active = db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=? AND user_id=? AND status='running'", Integer.class, input.runId(), principal.userId());
            if (active == 0) throw ApiException.forbidden("运行不存在或已经暂停");
        }
        String stdin = input.stdin() == null ? "" : input.stdin();
        if (stdin.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) throw ApiException.badRequest("脚本输入不能超过 64 KB");
        SkillService.SkillDetail skill = skills.detail(principal.userId(), input.skillId());
        RestrictedProcess.Plan plan = sandbox.skill(skills.executablePackage(principal.userId(), input.skillId()), input.script(), input.args());
        if (input.expectedHash() != null && !input.expectedHash().equals(plan.fingerprint())) throw ApiException.conflict("技能脚本已变更，请重新开始运行");
        Map<String,Object> operation = new LinkedHashMap<>();
        operation.put("skillId", input.skillId()); operation.put("skillName", skill.view().name()); operation.put("skillVersion", skill.view().version());
        operation.put("script", input.script()); operation.put("args", plan.arguments()); operation.put("stdin", stdin);
        operation.put("packageHash", plan.fingerprint()); operation.put("isolation", "无网络、无宿主文件写入、禁止派生进程");
        String snapshot = stringify(operation);
        if (snapshot.length() > 1_000_000) throw ApiException.badRequest("脚本参数过大");
        String job = UUID.randomUUID().toString(), action = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        transaction.executeWithoutResult(tx -> {
            if (skills.detail(principal.userId(), input.skillId()).view().version() != skill.view().version()) throw ApiException.conflict("技能版本已变化，请重新发起");
            db.update("INSERT INTO workflow_jobs(id,user_id,run_id,connection_id,stage,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    job, principal.userId(), input.runId(), "skill:" + input.skillId(), "execute", "WAITING_CONFIRMATION", now, now);
            db.update("INSERT INTO skill_script_actions(id,job_id,user_id,skill_id,skill_version,operation_json,operation_digest,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    action, job, principal.userId(), input.skillId(), skill.view().version(), snapshot,
                    digest(principal.userId(), job, action, input.skillId(), skill.view().version(), snapshot), "PENDING", now + 600_000L, now, now);
            event(job, "validate", "DONE", "技能和脚本已校验");
            event(job, "execute", "WAITING_CONFIRMATION", "脚本执行等待用户确认");
        });
        return workflows.getJob(principal.userId(), job);
    }

    public WorkflowService.JobView approve(Principal principal, String id) {
        requireUser(principal);
        Claim claim = transaction.execute(tx -> claim(principal.userId(), id));
        if (claim == null) throw ApiException.conflict("脚本无法认领");
        if (claim.expired()) { events.publishEvent(new WorkflowService.JobFinished(claim.action().jobId())); throw ApiException.conflict("脚本确认已过期或技能已变更，请重新发起"); }
        if (claim.plan() != null) execute(claim);
        WorkflowService.JobView job = workflows.getJob(principal.userId(), claim.action().jobId());
        if (Set.of("COMPLETED", "FAILED", "UNKNOWN", "REJECTED", "EXPIRED").contains(job.status())) events.publishEvent(new WorkflowService.JobFinished(job.id()));
        return job;
    }

    private Claim claim(String userId, String id) {
        Action action = action(userId, id);
        if (!action.status().equals("PENDING")) return new Claim(action, null, false);
        String digest = digest(userId, action.jobId(), id, action.skillId(), action.version(), action.operation());
        if (!vault.matches(action.digest(), digest)) throw ApiException.conflict("脚本操作内容校验失败");
        JsonNode operation = parse(action.operation()); RestrictedProcess.Plan plan = null;
        try {
            var current = skills.detail(userId, action.skillId());
            if (action.expiresAt() > System.currentTimeMillis() && current.view().enabled() && current.view().version() == action.version()) {
                List<String> args = json.convertValue(operation.path("args"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                var candidate = sandbox.skill(skills.executablePackage(userId, action.skillId()), operation.path("script").asText(), args);
                if (candidate.fingerprint().equals(operation.path("packageHash").asText())) plan = candidate;
            }
        } catch (Exception ignored) { /* Missing/changed package expires the approval. */ }
        if (plan == null) {
            db.update("UPDATE skill_script_actions SET status='EXPIRED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING'", System.currentTimeMillis(), id, userId);
            job(action.jobId(), "EXPIRED", null, "确认已过期或技能已变更"); event(action.jobId(), "execute", "EXPIRED", "确认已过期或技能已变更");
            return new Claim(action, null, true);
        }
        int changed = db.update("UPDATE skill_script_actions SET status='APPROVED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING' AND expires_at>?", System.currentTimeMillis(), id, userId, System.currentTimeMillis());
        if (changed == 0) throw ApiException.conflict("确认状态已变化");
        if (db.update("UPDATE skill_script_actions SET status='EXECUTING',updated_at=? WHERE id=? AND user_id=? AND status='APPROVED'", System.currentTimeMillis(), id, userId) == 0) throw ApiException.conflict("脚本已被认领");
        job(action.jobId(), "RUNNING", null, null); event(action.jobId(), "execute", "RUNNING", "用户已确认，正在执行受限脚本");
        return new Claim(action, plan, false);
    }

    private void execute(Claim claim) {
        Action action = claim.action();
        Process running = null;
        try {
            JsonNode operation = parse(action.operation());
            Process process = sandbox.process(claim.plan()).start(); running = process;
            var stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            var stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            var input = CompletableFuture.runAsync(() -> {
                try (var stream = process.getOutputStream()) { stream.write(operation.path("stdin").asText("").getBytes(StandardCharsets.UTF_8)); }
                catch (java.io.IOException ignored) { /* A script may intentionally not consume stdin. */ }
            });
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("脚本执行超时"); }
            input.get(5, TimeUnit.SECONDS);
            byte[] out = stdout.get(5, TimeUnit.SECONDS), err = stderr.get(5, TimeUnit.SECONDS);
            if (out.length > MAX_OUTPUT || err.length > MAX_OUTPUT) throw new IllegalStateException("脚本输出超过限制");
            String status = process.exitValue() == 0 ? "COMPLETED" : "FAILED";
            Map<String,Object> result = Map.of("exitCode", process.exitValue(), "stdout", new String(out, StandardCharsets.UTF_8), "stderr", new String(err, StandardCharsets.UTF_8));
            complete(action, status, stringify(result), status.equals("FAILED") ? "脚本返回非零退出码" : null);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); complete(action, "UNKNOWN", null, "脚本执行被中断，请核查"); }
        catch (Exception e) { complete(action, "FAILED", null, e.getMessage() == null ? "脚本执行失败" : e.getMessage()); }
        finally { if (running != null && running.isAlive()) running.destroyForcibly(); }
    }
    private byte[] read(java.io.InputStream stream) { try (stream) { return stream.readNBytes(MAX_OUTPUT + 1); } catch (java.io.IOException e) { throw new IllegalStateException("无法读取脚本输出", e); } }
    private void complete(Action action, String status, String result, String error) {
        transaction.executeWithoutResult(tx -> {
            db.update("UPDATE skill_script_actions SET status=?,error=?,updated_at=? WHERE id=? AND status='EXECUTING'", status.equals("COMPLETED") ? "SUCCEEDED" : status, error, System.currentTimeMillis(), action.id());
            job(action.jobId(), status, result, error); event(action.jobId(), "execute", status, error == null ? "受限脚本已返回结果" : error);
        });
    }
    public WorkflowService.JobView reject(Principal principal, String id) {
        requireUser(principal); Action action = action(principal.userId(), id);
        transaction.executeWithoutResult(tx -> {
            if (db.update("UPDATE skill_script_actions SET status='REJECTED',updated_at=? WHERE id=? AND user_id=? AND status='PENDING'", System.currentTimeMillis(), id, principal.userId()) == 0) throw ApiException.conflict("脚本已处理");
            job(action.jobId(), "REJECTED", null, "用户拒绝执行"); event(action.jobId(), "execute", "REJECTED", "用户拒绝执行");
        });
        events.publishEvent(new WorkflowService.JobFinished(action.jobId()));
        return workflows.getJob(principal.userId(), action.jobId());
    }
    public WorkflowService.ActionView getAction(String userId, String id) { Action a = action(userId, id); return new WorkflowService.ActionView(a.id(), a.jobId(), a.skillId(), a.version(), parse(a.operation()), a.status(), a.expiresAt(), "SKILL_SCRIPT"); }
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void expirePending() {
        List<Action> actions = db.query("SELECT id,job_id,skill_id,skill_version,operation_json,operation_digest,status,expires_at FROM skill_script_actions WHERE status='PENDING' AND expires_at<=? LIMIT 500", (rs, n) -> row(rs), System.currentTimeMillis());
        for (Action action : actions) {
            Boolean changed = transaction.execute(tx -> {
                if (db.update("UPDATE skill_script_actions SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING' AND expires_at<=?", System.currentTimeMillis(), action.id(), System.currentTimeMillis()) == 0) return false;
                job(action.jobId(), "EXPIRED", null, "确认已过期"); event(action.jobId(), "execute", "EXPIRED", "确认已过期"); return true;
            });
            if (Boolean.TRUE.equals(changed)) events.publishEvent(new WorkflowService.JobFinished(action.jobId()));
        }
    }
    private Action action(String userId, String id) {
        List<Action> rows = db.query("SELECT id,job_id,skill_id,skill_version,operation_json,operation_digest,status,expires_at FROM skill_script_actions WHERE user_id=? AND id=?", (rs, n) -> row(rs), userId, id);
        if (rows.isEmpty()) throw ApiException.notFound("脚本确认动作不存在"); return rows.get(0);
    }
    private Action row(java.sql.ResultSet rs) throws java.sql.SQLException { return new Action(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)); }
    private void job(String id, String status, String result, String error) { db.update("UPDATE workflow_jobs SET status=?,stage=?,result_json=?,error=?,updated_at=? WHERE id=?", status, status.equals("COMPLETED") ? "summarize" : "execute", result, error, System.currentTimeMillis(), id); }
    private void event(String id, String stage, String status, String message) { db.update("INSERT INTO workflow_events(job_id,stage,status,message,created_at) VALUES(?,?,?,?,?)", id, stage, status, message, System.currentTimeMillis()); }
    private void requireUser(Principal principal) { if (!principal.canApprove()) throw ApiException.forbidden("只有已登录用户可以确认脚本"); }
    private String digest(String user, String job, String id, String skill, int version, String operation) { return vault.digest("skill-script-action", user + "\0" + job + "\0" + id + "\0" + skill + "\0" + version + "\0" + operation); }
    private String stringify(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("无法保存脚本数据", e); } }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch (Exception e) { throw new IllegalStateException("无法读取脚本数据", e); } }
    private record Action(String id, String jobId, String skillId, int version, String operation, String digest, String status, long expiresAt) {}
    private record Claim(Action action, RestrictedProcess.Plan plan, boolean expired) {}
    public record Input(String skillId, String script, List<String> args, String stdin, String runId, String expectedHash) {}
}
