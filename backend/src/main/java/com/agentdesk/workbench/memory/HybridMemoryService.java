package com.agentdesk.workbench.memory;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.models.ModelService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete-turn session memory. SQLite remains authoritative; Redis only accelerates reads. */
@Service
public class HybridMemoryService {
    private static final Logger log = LoggerFactory.getLogger(HybridMemoryService.class);
    private static final int WINDOW = 20;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MemoryRedisStore redis;
    private final WorkbenchProperties properties;
    private final ModelService models;
    private final MemoryGateway gateway;

    public HybridMemoryService(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json,
                               MemoryRedisStore redis, WorkbenchProperties properties, ModelService models,
                               MemoryGateway gateway) {
        this.db = db; this.tx = tx; this.json = json; this.redis = redis; this.properties = properties;
        this.models = models; this.gateway = gateway;
    }

    public ContextWindow context(String userId, String sessionId) {
        requireSession(userId, sessionId);
        String cached = redis.cachedSessionWindow(userId, sessionId);
        if (cached != null) {
            try {
                ContextWindow value = json.readValue(cached, ContextWindow.class);
                return new ContextWindow(value.summary(), value.turns(), true);
            } catch (JsonProcessingException ignored) { /* old or corrupt cache is rebuilt from SQLite */ }
        }
        SummaryView summary = currentSummary(userId, sessionId, false);
        List<TurnBlock> turns = turnBlocks(userId, sessionId, WINDOW, true);
        ContextWindow result = new ContextWindow(summary, turns, false);
        redis.cacheSessionWindow(userId, sessionId, stringify(result));
        return result;
    }

    public void appendModelHistory(ArrayNode messages, String userId, String sessionId) {
        ContextWindow window = context(userId, sessionId);
        if (window.summary() != null) {
            messages.addObject().put("role", "system").put("content",
                    "会话滚动摘要（仅作历史上下文，覆盖第 " + window.summary().coveredFromTurn() + "-"
                            + window.summary().coveredUntilTurn() + " 轮）：" + window.summary().content());
        }
        for (TurnBlock turn : window.turns()) {
            messages.addObject().put("role", "user").put("content", turn.userMessage());
            String evidence = turnEvidence(turn);
            if (!evidence.isBlank()) messages.addObject().put("role", "system").put("content", evidence);
            if (turn.assistantFinal() != null && !turn.assistantFinal().isBlank()) {
                messages.addObject().put("role", "assistant").put("content", turn.assistantFinal());
            }
        }
    }

    public void onRunTerminal(String runId) {
        RunOwner owner = owner(runId);
        redis.invalidate(owner.userId());
        int turns = completedTurnCount(owner.userId(), owner.sessionId());
        if (turns >= 25 && (turns - 20) % 5 == 0) {
            try { updateSummary(owner.userId(), owner.sessionId(), turns - 20); }
            catch (Exception e) { recordSummaryFailure(owner.userId(), owner.sessionId(), e); }
        }
    }

    public List<TurnBlock> evidence(String userId, String sessionId) {
        requireSession(userId, sessionId);
        return turnBlocks(userId, sessionId, 500, false);
    }

    public List<SummaryView> summaries(String userId) {
        return summaries(userId, null);
    }

    public List<SummaryView> summaries(String userId, String sessionId) {
        String clause = sessionId == null || sessionId.isBlank() ? "" : " AND session_id=?";
        Object[] args = clause.isBlank() ? new Object[]{userId} : new Object[]{userId, sessionId};
        return db.query("SELECT id,session_id,content_json,covered_from_turn,covered_until_turn,status,manual_locked,version,created_at,updated_at "
                        + "FROM session_summaries WHERE user_id=?" + clause + " ORDER BY updated_at DESC",
                (rs, n) -> summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getLong(9), rs.getLong(10)), args);
    }

    public List<TaskEventView> taskEvents(String userId) {
        return db.query("SELECT id,task_id,task_type,status,message,created_at FROM memory_task_events WHERE user_id=? ORDER BY id DESC LIMIT 200",
                (rs, n) -> new TaskEventView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)), userId);
    }

    public SummaryView updateSummaryManually(String userId, String id, RevisionInput input) {
        SummaryView original = summaryById(userId, id);
        requireVersion(original.version(), input.expectedVersion());
        String content = requiredContent(input.content());
        tx.executeWithoutResult(ignored -> {
            revision(userId, "SUMMARY", id, original.version(), original.content(), content, "EDIT");
            int changed = db.update("UPDATE session_summaries SET content_json=?,manual_locked=1,status='ACTIVE',version=version+1,updated_at=? WHERE id=? AND user_id=? AND version=?",
                    stringify(Map.of("manual", content)), System.currentTimeMillis(), id, userId, original.version());
            if (changed == 0) throw ApiException.conflict("摘要已被其他操作更新，请刷新后重试");
        });
        redis.invalidate(userId);
        return summaryById(userId, id);
    }

    public void reviseL0(String userId, String runId, RevisionInput input) {
        TurnBlock original = evidenceByRun(userId, runId);
        int version = latestRevisionVersion(userId, "L0", runId);
        requireVersion(version, input.expectedVersion());
        String content = requiredContent(input.content());
        revision(userId, "L0", runId, version, stringify(original), content, "EDIT");
        redis.invalidate(userId);
    }

    public void softDelete(String userId, String type, String id, Integer expectedVersion) {
        String normalized = normalizeType(type);
        if (normalized.equals("L0")) {
            TurnBlock original = evidenceByRun(userId, id);
            int version = latestRevisionVersion(userId, normalized, id);
            requireVersion(version, expectedVersion);
            revision(userId, normalized, id, version, stringify(original), "", "DELETE");
        } else if (normalized.equals("SUMMARY")) {
            SummaryView original = summaryById(userId, id);
            requireVersion(original.version(), expectedVersion);
            revision(userId, normalized, id, original.version(), original.content(), "", "DELETE");
            db.update("UPDATE session_summaries SET status='DELETED',deleted_at=?,deleted_by=?,version=version+1,updated_at=? WHERE id=? AND user_id=?",
                    System.currentTimeMillis(), userId, System.currentTimeMillis(), id, userId);
        } else throw ApiException.badRequest("该类型由长期记忆接口处理");
        redis.invalidate(userId);
    }

    public void restore(String userId, String type, String id) {
        String normalized = normalizeType(type);
        if (normalized.equals("L0")) {
            evidenceByRun(userId, id);
            int version = latestRevisionVersion(userId, normalized, id);
            revision(userId, normalized, id, version, "", "", "RESTORE");
        } else if (normalized.equals("SUMMARY")) {
            SummaryView original = summaryById(userId, id);
            revision(userId, normalized, id, original.version(), original.content(), original.content(), "RESTORE");
            db.update("UPDATE session_summaries SET status='ACTIVE',deleted_at=NULL,deleted_by=NULL,version=version+1,updated_at=? WHERE id=? AND user_id=?",
                    System.currentTimeMillis(), id, userId);
        } else throw ApiException.badRequest("该类型由长期记忆接口处理");
        redis.invalidate(userId);
    }

    public List<RevisionView> revisions(String userId, String type, String id) {
        return db.query("SELECT id,version,original_content,revised_content,operation,manual,created_at FROM memory_revisions "
                        + "WHERE user_id=? AND target_type=? AND target_id=? ORDER BY version DESC",
                (rs, n) -> new RevisionView(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getLong(7)), userId, normalizeType(type), id);
    }

    public void rollback(String userId, String type, String id, RollbackInput input) {
        String normalized = normalizeType(type);
        RevisionView target = db.query("SELECT id,version,original_content,revised_content,operation,manual,created_at FROM memory_revisions "
                        + "WHERE user_id=? AND target_type=? AND target_id=? AND version=?",
                rs -> rs.next() ? new RevisionView(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getLong(7)) : null,
                userId, normalized, id, input.version());
        if (target == null) throw ApiException.notFound("历史版本不存在");
        if (normalized.equals("L0")) reviseL0(userId, id, new RevisionInput(target.revisedContent(), latestRevisionVersion(userId, normalized, id)));
        else if (normalized.equals("SUMMARY")) updateSummaryManually(userId, id, new RevisionInput(target.revisedContent(), summaryById(userId, id).version()));
        else throw ApiException.badRequest("该类型由长期记忆接口处理");
    }

    public Map<String, Object> rebuild(String userId, RebuildInput input) {
        String type = input == null || input.type() == null ? "SUMMARY" : input.type().toUpperCase();
        if (properties.memoryPipelineDryrun()) {
            log.info("memoryPipelineDryrun=true userId={} plannedRebuild={} sessionId={}", userId, type, input == null ? null : input.sessionId());
            return Map.of("status", "DRYRUN", "type", type);
        }
        if (!type.equals("SUMMARY")) {
            redis.invalidate(userId);
            return Map.of("status", "QUEUED", "type", type, "message", "索引缓存已失效，将由流水线按依赖重建");
        }
        if (input == null || input.sessionId() == null || input.sessionId().isBlank()) throw ApiException.badRequest("重建摘要必须指定 sessionId");
        updateSummary(userId, input.sessionId(), Math.max(0, completedTurnCount(userId, input.sessionId()) - WINDOW));
        return Map.of("status", "COMPLETED", "type", type);
    }

    private List<TurnBlock> turnBlocks(String userId, String sessionId, int limit, boolean activeOnly) {
        List<RawTurn> raw = db.query("SELECT r.id,r.status,r.mode,r.created_at,m.content,c.messages_json "
                        + "FROM runs r JOIN run_contexts c ON c.run_id=r.id JOIN messages m ON m.id=c.source_message_id "
                        + "WHERE r.user_id=? AND r.session_id=? AND r.status NOT IN ('queued','running','waiting_confirmation','cancel_requested') "
                        + "ORDER BY r.created_at DESC,r.rowid DESC LIMIT ?",
                (rs, n) -> new RawTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)),
                userId, sessionId, limit);
        int offset = Math.max(0, completedTurnCount(userId, sessionId) - raw.size());
        List<TurnBlock> result = new ArrayList<>();
        for (int index = raw.size() - 1; index >= 0; index--) {
            RawTurn item = raw.get(index);
            LatestRevision revision = latestRevision(userId, "L0", item.runId());
            if (activeOnly && revision != null && revision.operation().equals("DELETE")) continue;
            List<EventEvidence> events = db.query("SELECT seq,type,payload,created_at FROM run_events WHERE run_id=? AND type IN ('tool_started','confirmation_required','tool_result','run_completed','run_failed','run_cancelled') ORDER BY seq",
                    (rs, n) -> new EventEvidence(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), item.runId());
            String assistant = finalAssistant(item.messagesJson(), item.userMessage());
            result.add(new TurnBlock(item.runId(), offset + result.size() + 1, item.status(), item.mode(), item.userMessage(), assistant,
                    events, revision == null ? null : revision.revisedContent(), revision == null ? 1 : revision.version(),
                    revision != null && revision.operation().equals("DELETE"), item.createdAt()));
        }
        return result;
    }

    private String finalAssistant(String messagesJson, String userMessage) {
        try {
            JsonNode messages = json.readTree(messagesJson);
            int source = -1;
            for (int i = 0; i < messages.size(); i++) if (messages.get(i).path("role").asText().equals("user") && messages.get(i).path("content").asText().equals(userMessage)) source = i;
            String result = null;
            for (int i = source + 1; i < messages.size(); i++) if (messages.get(i).path("role").asText().equals("assistant") && messages.get(i).path("content").isTextual()) result = messages.get(i).path("content").asText();
            return result;
        } catch (Exception ignored) { return null; }
    }

    private String turnEvidence(TurnBlock turn) {
        if (turn.events().isEmpty()) return "";
        List<Map<String, Object>> compact = turn.events().stream().map(event -> compactEvent(turn.runId(), event)).toList();
        return "第 " + turn.turn() + " 轮真实执行证据（状态=" + turn.status() + "，不得改写为未验证推测）：" + stringify(compact)
                + (turn.userRevision() == null ? "" : "；用户修订说明（不改变原始工具结果和状态机）：" + turn.userRevision());
    }

    private Map<String, Object> compactEvent(String runId, EventEvidence event) {
        JsonNode payload = parse(event.payload());
        String raw = event.payload();
        if (raw.length() <= 2000) return Map.of("type", event.type(), "payload", payload, "resultRef", runId + ":" + event.seq());
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("type", event.type());
        summary.put("status", payload.path("status").asText("UNKNOWN"));
        if (payload.has("affectedRows")) summary.put("affectedRows", payload.path("affectedRows").asLong());
        if (payload.has("error")) summary.put("error", payload.path("error").asText());
        summary.put("truncated", true);
        summary.put("resultRef", runId + ":" + event.seq());
        return summary;
    }

    private void updateSummary(String userId, String sessionId, int coveredUntil) {
        if (coveredUntil <= 0) return;
        if (properties.memoryPipelineDryrun()) {
            log.info("memoryPipelineDryrun=true plannedSummary userId={} sessionId={} coveredUntil={}", userId, sessionId, coveredUntil);
            return;
        }
        long now = System.currentTimeMillis();
        SummaryView existing = currentSummary(userId, sessionId, false);
        List<TurnBlock> all = turnBlocks(userId, sessionId, 500, true);
        int from = existing == null ? 1 : existing.coveredUntilTurn() + 1;
        List<TurnBlock> delta = all.stream().filter(t -> t.turn() >= from && t.turn() <= coveredUntil).toList();
        if (delta.isEmpty()) return;
        ModelService.ModelConnection model = models.resolveChat(userId, null);
        String content = gateway.summarize(model.baseUrl(), model.modelName(), model.apiKey(), existing == null ? null : existing.content(), stringify(delta));
        if (existing == null) db.update("INSERT INTO session_summaries(id,user_id,session_id,content_json,covered_from_turn,covered_until_turn,model_id,model_version,status,created_at,updated_at) VALUES(?,?,?,?,1,?,?,?,'ACTIVE',?,?)",
                UUID.randomUUID().toString(), userId, sessionId, content, coveredUntil, model.id(), model.version(), now, now);
        else if (!existing.manualLocked()) db.update("UPDATE session_summaries SET content_json=?,covered_until_turn=?,model_id=?,model_version=?,version=version+1,status='ACTIVE',updated_at=? WHERE id=? AND user_id=?",
                content, coveredUntil, model.id(), model.version(), now, existing.id(), userId);
        else db.update("INSERT INTO memory_task_events(user_id,task_id,task_type,status,message,created_at) VALUES(?,?,?,?,?,?)",
                userId, existing.id(), "SUMMARY_PROPOSAL", "PENDING", content, now);
        redis.invalidate(userId);
    }

    private void recordSummaryFailure(String userId, String sessionId, Exception error) {
        String message = error.getMessage() == null ? "摘要生成失败" : error.getMessage();
        log.warn("sessionSummaryFailed=true userId={} sessionId={} reason={}", userId, sessionId, message);
        db.update("INSERT INTO memory_task_events(user_id,task_id,task_type,status,message,created_at) VALUES(?,?,?,?,?,?)",
                userId, UUID.randomUUID().toString(), "SUMMARY", "FAILED", message, System.currentTimeMillis());
    }

    private SummaryView currentSummary(String userId, String sessionId, boolean includeDeleted) {
        return db.query("SELECT id,session_id,content_json,covered_from_turn,covered_until_turn,status,manual_locked,version,created_at,updated_at FROM session_summaries WHERE user_id=? AND session_id=?"
                        + (includeDeleted ? "" : " AND status='ACTIVE'"),
                rs -> rs.next() ? summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getLong(9), rs.getLong(10)) : null,
                userId, sessionId);
    }

    public SummaryView summaryById(String userId, String id) {
        SummaryView value = db.query("SELECT id,session_id,content_json,covered_from_turn,covered_until_turn,status,manual_locked,version,created_at,updated_at FROM session_summaries WHERE user_id=? AND id=?",
                rs -> rs.next() ? summary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getLong(9), rs.getLong(10)) : null, userId, id);
        if (value == null) throw ApiException.notFound("会话摘要不存在");
        return value;
    }

    private SummaryView summary(String id, String sessionId, String raw, int from, int until, String status, boolean locked, int version, long created, long updated) {
        JsonNode node = parse(raw);
        String content = node.has("manual") ? node.path("manual").asText() : raw;
        return new SummaryView(id, sessionId, content, from, until, status, locked, version, created, updated);
    }

    public TurnBlock evidenceByRun(String userId, String runId) {
        RunOwner owner = owner(runId);
        if (!owner.userId().equals(userId)) throw ApiException.notFound("原始证据不存在");
        return turnBlocks(userId, owner.sessionId(), 500, false).stream().filter(t -> t.runId().equals(runId)).findFirst().orElseThrow(() -> ApiException.notFound("原始证据不存在"));
    }

    private RunOwner owner(String runId) {
        RunOwner owner = db.query("SELECT user_id,session_id FROM runs WHERE id=?", rs -> rs.next() ? new RunOwner(rs.getString(1), rs.getString(2)) : null, runId);
        if (owner == null) throw ApiException.notFound("运行不存在");
        return owner;
    }

    private int completedTurnCount(String userId, String sessionId) {
        return db.queryForObject("SELECT COUNT(*) FROM runs WHERE user_id=? AND session_id=? AND status NOT IN ('queued','running','waiting_confirmation','cancel_requested')", Integer.class, userId, sessionId);
    }

    private void requireSession(String userId, String sessionId) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=? AND user_id=?", Integer.class, sessionId, userId);
        if (count == null || count == 0) throw ApiException.notFound("会话不存在");
    }

    private void revision(String userId, String type, String id, int currentVersion, String original, String revised, String operation) {
        db.update("INSERT INTO memory_revisions(id,user_id,target_type,target_id,version,original_content,revised_content,operation,manual,created_at) VALUES(?,?,?,?,?,?,?,?,1,?)",
                UUID.randomUUID().toString(), userId, type, id, currentVersion + 1, original == null ? "" : original, revised == null ? "" : revised, operation, System.currentTimeMillis());
    }

    private LatestRevision latestRevision(String userId, String type, String id) {
        return db.query("SELECT version,revised_content,operation FROM memory_revisions WHERE user_id=? AND target_type=? AND target_id=? ORDER BY version DESC LIMIT 1",
                rs -> rs.next() ? new LatestRevision(rs.getInt(1), rs.getString(2), rs.getString(3)) : null, userId, type, id);
    }
    private int latestRevisionVersion(String userId, String type, String id) { LatestRevision value = latestRevision(userId, type, id); return value == null ? 1 : value.version(); }
    private String normalizeType(String type) { String value = type == null ? "" : type.toUpperCase(); if (!List.of("L0","SUMMARY","L1","L2","L3").contains(value)) throw ApiException.badRequest("记忆类型无效"); return value; }
    private void requireVersion(int actual, Integer expected) { if (expected == null) throw ApiException.badRequest("expectedVersion 不能为空"); if (actual != expected) throw ApiException.conflict("版本已变化，请刷新后重试"); }
    private String requiredContent(String value) { if (value == null || value.isBlank() || value.length() > 20_000) throw ApiException.badRequest("记忆内容无效"); return value.trim(); }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch (JsonProcessingException e) { return json.createObjectNode().put("raw", value); } }
    private String stringify(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("无法序列化记忆数据", e); } }

    private record RawTurn(String runId, String status, String mode, long createdAt, String userMessage, String messagesJson) {}
    private record RunOwner(String userId, String sessionId) {}
    private record LatestRevision(int version, String revisedContent, String operation) {}
    public record EventEvidence(long seq, String type, String payload, long createdAt) {}
    public record TurnBlock(String runId, int turn, String status, String mode, String userMessage, String assistantFinal,
                            List<EventEvidence> events, String userRevision, int version, boolean deleted, long createdAt) {}
    public record SummaryView(String id, String sessionId, String content, int coveredFromTurn, int coveredUntilTurn,
                              String status, boolean manualLocked, int version, long createdAt, long updatedAt) {}
    public record ContextWindow(SummaryView summary, List<TurnBlock> turns, boolean cacheHit) {}
    public record RevisionInput(String content, Integer expectedVersion) {}
    public record RollbackInput(int version) {}
    public record RebuildInput(String type, String sessionId) {}
    public record RevisionView(String id, int version, String originalContent, String revisedContent, String operation, boolean manual, long createdAt) {}
    public record TaskEventView(long id, String taskId, String taskType, String status, String message, long createdAt) {}
}
