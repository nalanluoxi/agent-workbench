package com.agentdesk.workbench.memory;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.config.WorkbenchProperties;
import com.agentdesk.workbench.models.ModelService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(password|passwd|api[ _-]?key|secret|token|密码|验证码|密钥|授权码)\\s*[:=：]\\s*\\S+|-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ModelService models;
    private final MemoryGateway gateway;
    private final SecretVault vault;
    private final ObjectMapper json;
    private final MemoryRedisStore redis;
    private final WorkbenchProperties properties;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final ExecutorService recallWorkers = Executors.newCachedThreadPool();

    public MemoryService(JdbcTemplate db, TransactionTemplate tx, ModelService models, MemoryGateway gateway, SecretVault vault,
                         ObjectMapper json, MemoryRedisStore redis, WorkbenchProperties properties) {
        this.db = db; this.tx = tx; this.models = models; this.gateway = gateway; this.vault = vault; this.json = json; this.redis = redis; this.properties = properties;
    }

    public List<MemoryView> list(String userId, String layer, String search) {
        if (layer != null && !layer.isBlank() && !Set.of("L1", "L2", "L3").contains(layer)) throw ApiException.badRequest("记忆层级无效");
        return db.query("SELECT id,layer,content,source_session_id,source_message_id,manual,status,version,created_at,updated_at,source_kind,confidence,verified,manual_locked,deleted_at FROM memories "
                        + "WHERE user_id=? AND (?='' OR layer=?) AND (?='' OR instr(content,?)>0) ORDER BY updated_at DESC LIMIT 500",
                (rs, n) -> memoryView(rs),
                userId, text(layer), text(layer), text(search), text(search));
    }

    public MemoryView get(String userId, String id) {
        MemoryView result = db.query("SELECT id,layer,content,source_session_id,source_message_id,manual,status,version,created_at,updated_at,source_kind,confidence,verified,manual_locked,deleted_at FROM memories WHERE user_id=? AND id=?",
                rs -> rs.next() ? memoryView(rs) : null, userId, id);
        if (result == null) throw ApiException.notFound("记忆不存在");
        return result;
    }

    public MemoryView create(String userId, MemoryInput input) {
        validate(input);
        String id = UUID.randomUUID().toString(), content = input.content().trim();
        long now = System.currentTimeMillis();
        db.update("INSERT INTO memories(id,user_id,layer,content,fingerprint,manual,status,created_at,updated_at) VALUES(?,?,?,?,?,1,'ACTIVE',?,?)",
                id, userId, input.layer(), content, fingerprint(content), now, now);
        redis.invalidate(userId);
        return get(userId, id);
    }

    public MemoryView update(String userId, String id, MemoryInput input) {
        validate(input);
        MemoryView original = get(userId, id);
        if (input.expectedVersion() != null && input.expectedVersion() != original.version()) throw ApiException.conflict("记忆已被其他操作更新，请刷新后重试");
        if (!original.layer().equals(input.layer())) throw ApiException.badRequest("不能更改记忆层级");
        tx.executeWithoutResult(status -> {
            saveRevision(userId, original, input.content().trim(), "EDIT");
            suppress(userId, original);
            invalidateDependents(userId, id);
            db.update("DELETE FROM memory_embeddings WHERE memory_id=?", id);
            db.update("UPDATE memories SET content=?,fingerprint=?,manual=1,manual_locked=1,status='ACTIVE',deleted_at=NULL,deleted_by=NULL,version=version+1,updated_at=? WHERE id=? AND user_id=?",
                    input.content().trim(), fingerprint(input.content().trim()), System.currentTimeMillis(), id, userId);
        });
        redis.invalidate(userId);
        return get(userId, id);
    }

    public void delete(String userId, String id) {
        MemoryView original = get(userId, id);
        tx.executeWithoutResult(status -> {
            saveRevision(userId, original, "", "DELETE");
            suppress(userId, original);
            invalidateDependents(userId, id);
            db.update("DELETE FROM memory_embeddings WHERE memory_id=?", id);
            db.update("UPDATE memories SET status='DELETED',deleted_at=?,deleted_by=?,version=version+1,updated_at=? WHERE id=? AND user_id=?",
                    System.currentTimeMillis(), userId, System.currentTimeMillis(), id, userId);
        });
        redis.invalidate(userId);
    }

    public MemoryView restore(String userId, String id) {
        MemoryView original = get(userId, id);
        if (!original.status().equals("DELETED")) throw ApiException.conflict("该记忆未被删除");
        tx.executeWithoutResult(status -> {
            saveRevision(userId, original, original.content(), "RESTORE");
            db.update("UPDATE memories SET status='ACTIVE',deleted_at=NULL,deleted_by=NULL,version=version+1,updated_at=? WHERE id=? AND user_id=?",
                    System.currentTimeMillis(), id, userId);
        });
        redis.invalidate(userId);
        return get(userId, id);
    }

    public List<RevisionView> revisions(String userId, String id) {
        get(userId, id);
        return db.query("SELECT id,version,original_content,revised_content,operation,manual,created_at FROM memory_revisions WHERE user_id=? AND target_type IN ('L1','L2','L3') AND target_id=? ORDER BY version DESC",
                (rs, n) -> new RevisionView(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getLong(7)), userId, id);
    }

    public MemoryView rollback(String userId, String id, int version) {
        MemoryView current = get(userId, id);
        String content = db.query("SELECT revised_content FROM memory_revisions WHERE user_id=? AND target_id=? AND version=?",
                rs -> rs.next() ? rs.getString(1) : null, userId, id, version);
        if (content == null || content.isBlank()) throw ApiException.notFound("可恢复的历史版本不存在");
        return update(userId, id, new MemoryInput(current.layer(), content, current.version()));
    }

    public List<ProposalView> proposals(String userId) {
        return db.query("SELECT id,memory_id,proposed_content,source_json,status,created_at,updated_at FROM memory_change_proposals WHERE user_id=? ORDER BY updated_at DESC",
                (rs, n) -> new ProposalView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)), userId);
    }

    public ProposalView decideProposal(String userId, String proposalId, boolean approve) {
        ProposalView proposal = db.query("SELECT id,memory_id,proposed_content,source_json,status,created_at,updated_at FROM memory_change_proposals WHERE user_id=? AND id=?",
                rs -> rs.next() ? new ProposalView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)) : null,
                userId, proposalId);
        if (proposal == null) throw ApiException.notFound("更新建议不存在");
        if (!proposal.status().equals("PENDING")) throw ApiException.conflict("更新建议已经处理");
        if (approve) {
            MemoryView current = get(userId, proposal.memoryId());
            update(userId, current.id(), new MemoryInput(current.layer(), proposal.proposedContent(), current.version()));
        }
        db.update("UPDATE memory_change_proposals SET status=?,updated_at=? WHERE id=? AND user_id=? AND status='PENDING'",
                approve ? "APPROVED" : "REJECTED", System.currentTimeMillis(), proposalId, userId);
        return proposals(userId).stream().filter(item -> item.id().equals(proposalId)).findFirst().orElseThrow();
    }

    public Map<String, Object> rebuild(String userId, String type) {
        String normalized = type == null ? "INDEX" : type.toUpperCase(java.util.Locale.ROOT);
        if (properties.memoryPipelineDryrun()) {
            log.info("memoryPipelineDryrun=true userId={} plannedRebuild={} sqliteWrite=false redisWrite=false", userId, normalized);
            return Map.of("status", "DRYRUN", "type", normalized);
        }
        if (normalized.equals("INDEX") || normalized.equals("EMBEDDING")) db.update("DELETE FROM memory_embeddings WHERE memory_id IN (SELECT id FROM memories WHERE user_id=?)", userId);
        redis.invalidate(userId);
        long now = System.currentTimeMillis();
        db.update("INSERT INTO memory_task_events(user_id,task_id,task_type,status,message,created_at) VALUES(?,?,?,?,?,?)",
                userId, UUID.randomUUID().toString(), normalized, "COMPLETED", "已清理缓存和旧索引，将在下次召回时重建", now);
        return Map.of("status", "COMPLETED", "type", normalized);
    }

    public List<MemoryJob> jobs(String userId) {
        return db.query("SELECT run_id,session_id,status,error,attempts,created_at,updated_at FROM memory_jobs WHERE user_id=? ORDER BY updated_at DESC LIMIT 100",
                (rs, n) -> new MemoryJob(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7)), userId);
    }

    public MemoryJobPage jobs(String userId, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String normalized = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.isBlank() && !Set.of("QUEUED", "RUNNING", "FAILED", "COMPLETED", "SKIPPED", "INTERRUPTED").contains(normalized)) {
            throw ApiException.badRequest("不支持的记忆任务状态");
        }
        String clause = normalized.isBlank() ? "" : " AND status=?";
        Object[] queryArgs = normalized.isBlank()
                ? new Object[]{userId, safeSize, (safePage - 1) * safeSize}
                : new Object[]{userId, normalized, safeSize, (safePage - 1) * safeSize};
        List<MemoryJob> items = db.query("SELECT run_id,session_id,status,error,attempts,created_at,updated_at FROM memory_jobs WHERE user_id=?" + clause
                        + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> new MemoryJob(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7)), queryArgs);
        Object[] countArgs = normalized.isBlank() ? new Object[]{userId} : new Object[]{userId, normalized};
        long total = count("SELECT COUNT(*) FROM memory_jobs WHERE user_id=?" + clause, countArgs);
        return new MemoryJobPage(items, safePage, safeSize, total, jobStats(userId));
    }

    public void queue(String runId) {
        if (properties.memoryPipelineDryrun()) {
            log.info("memoryPipelineDryrun=true plannedExtraction runId={} externalModelCall=false sqliteWrite=false redisWrite=false", runId);
            return;
        }
        List<JobSource> source = db.query("SELECT r.user_id,r.session_id,c.source_message_id FROM runs r JOIN run_contexts c ON c.run_id=r.id WHERE r.id=? AND c.source_message_id IS NOT NULL",
                (rs, n) -> new JobSource(rs.getString(1), rs.getString(2), rs.getString(3)), runId);
        if (source.isEmpty()) return;
        JobSource value = source.get(0);
        int turns = db.queryForObject("SELECT COUNT(*) FROM runs WHERE user_id=? AND session_id=? AND status='completed'", Integer.class, value.userId(), value.sessionId());
        if (!(turns == 1 || turns == 2 || turns == 4 || turns >= 5 && turns % 5 == 0)) {
            log.info("memoryExtractionDeferred=true userId={} sessionId={} completedTurns={} nextPolicy=1-2-4-5x", value.userId(), value.sessionId(), turns);
            return;
        }
        enqueue(value, runId);
    }

    private void enqueue(JobSource value, String runId) {
        long now = System.currentTimeMillis();
        int inserted = db.update("INSERT OR IGNORE INTO memory_jobs(run_id,user_id,session_id,source_message_id,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                runId, value.userId(), value.sessionId(), value.messageId(), "QUEUED", now, now);
        if (inserted == 1) redis.enqueue(runId);
    }

    public void retry(String userId, String runId) {
        int changed = db.update("UPDATE memory_jobs SET status='QUEUED',error=NULL,updated_at=? WHERE run_id=? AND user_id=? AND status IN ('FAILED','INTERRUPTED')", System.currentTimeMillis(), runId, userId);
        if (changed == 0) throw ApiException.conflict("该提取任务不存在或当前不能重试");
        redis.enqueue(runId);
    }

    @EventListener
    public void recover(ApplicationReadyEvent ignored) {
        db.update("UPDATE memory_jobs SET status='QUEUED',error='服务重启，已重新进入 Redis 记忆队列',updated_at=? WHERE status='RUNNING'", System.currentTimeMillis());
        List<String> queued = db.query("SELECT run_id FROM memory_jobs WHERE status='QUEUED'", (rs, n) -> rs.getString(1));
        queued.forEach(redis::enqueue);
        workers.execute(this::consume);
    }

    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                MemoryRedisStore.StreamJob job = redis.take(2);
                if (job != null) {
                    extract(job.runId());
                    redis.acknowledge(job);
                } else enqueueIdleCompensation();
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                throw new IllegalStateException("Redis 记忆队列不可用", e);
            }
        }
    }

    private void enqueueIdleCompensation() {
        long idleBefore = System.currentTimeMillis() - 600_000;
        List<IdleJob> due = db.query("SELECT r.id,r.user_id,r.session_id,c.source_message_id FROM runs r JOIN run_contexts c ON c.run_id=r.id "
                        + "WHERE r.status='completed' AND r.updated_at<=? AND c.source_message_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM memory_jobs j WHERE j.run_id=r.id) "
                        + "AND r.id=(SELECT r2.id FROM runs r2 WHERE r2.user_id=r.user_id AND r2.session_id=r.session_id AND r2.status='completed' ORDER BY r2.created_at DESC,r2.rowid DESC LIMIT 1) LIMIT 20",
                (rs, n) -> new IdleJob(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), idleBefore);
        for (IdleJob item : due) enqueue(new JobSource(item.userId(), item.sessionId(), item.messageId()), item.runId());
    }

    public HotContext context(String userId, String sessionId) {
        String cached = redis.cachedContext(userId, sessionId);
        if (cached != null) {
            try {
                HotContext value = json.readValue(cached, HotContext.class);
                return enrichContext(userId, sessionId, value.summary(), value.persona(), value.scenes(), true);
            }
            catch (JsonProcessingException ignored) { /* rebuild corrupted/old cache */ }
        }
        Integer sessionCount = db.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=? AND user_id=?", Integer.class, sessionId, userId);
        if (sessionCount == null || sessionCount == 0) throw ApiException.notFound("会话不存在");
        List<MemoryView> persona = db.query("SELECT id,layer,content,source_session_id,source_message_id,manual,status,version,created_at,updated_at,source_kind,confidence,verified,manual_locked,deleted_at FROM memories WHERE user_id=? AND layer='L3' AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 5",
                (rs, n) -> memoryView(rs), userId);
        List<MemoryView> scenes = db.query("SELECT id,layer,content,source_session_id,source_message_id,manual,status,version,created_at,updated_at,source_kind,confidence,verified,manual_locked,deleted_at FROM memories WHERE user_id=? AND layer='L2' AND status='ACTIVE' AND (source_session_id=? OR source_session_id IS NULL) ORDER BY updated_at DESC LIMIT 5",
                (rs, n) -> memoryView(rs), userId, sessionId);
        String summary = scenes.stream().map(MemoryView::content).limit(3).reduce((a, b) -> a + "；" + b).orElse("暂无会话摘要");
        HotContext result = enrichContext(userId, sessionId, summary, persona, scenes, false);
        redis.cacheContext(userId, sessionId, stringify(result));
        return result;
    }

    private HotContext enrichContext(String userId, String sessionId, String legacySummary, List<MemoryView> persona,
                                     List<MemoryView> scenes, boolean cacheHit) {
        String sessionSummary = db.query("SELECT content_json FROM session_summaries WHERE user_id=? AND session_id=? AND status='ACTIVE' "
                        + "ORDER BY version DESC,updated_at DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : null, userId, sessionId);
        long totalL2 = count("SELECT COUNT(*) FROM memories WHERE user_id=? AND layer='L2' AND status='ACTIVE' AND deleted_at IS NULL", new Object[]{userId});
        long totalL3 = count("SELECT COUNT(*) FROM memories WHERE user_id=? AND layer='L3' AND status='ACTIVE' AND deleted_at IS NULL", new Object[]{userId});
        JobStats stats = jobStats(userId);
        return new HotContext(legacySummary, persona, scenes, stats.redisPending(), cacheHit,
                sessionSummary == null ? "尚未生成滚动摘要" : sessionSummary,
                new LayerCounts(scenes.size(), persona.size()), new LayerCounts(totalL2, totalL3), stats,
                "SESSION_CONTEXT", null);
    }

    private JobStats jobStats(String userId) {
        Map<String, Long> values = new java.util.HashMap<>();
        db.query("SELECT status,COUNT(*) FROM memory_jobs WHERE user_id=? GROUP BY status",
                rs -> { while (rs.next()) values.put(rs.getString(1), rs.getLong(2)); return null; }, userId);
        return new JobStats(values.getOrDefault("QUEUED", 0L), values.getOrDefault("RUNNING", 0L),
                values.getOrDefault("FAILED", 0L), values.getOrDefault("COMPLETED", 0L),
                values.getOrDefault("SKIPPED", 0L), values.getOrDefault("INTERRUPTED", 0L), redis.pendingJobs());
    }

    private long count(String sql, Object[] args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void extract(String runId) {
        int claimed = db.update("UPDATE memory_jobs SET status='RUNNING',attempts=attempts+1,updated_at=? WHERE run_id=? AND status='QUEUED'", System.currentTimeMillis(), runId);
        if (claimed == 0) return;
        try {
            ExtractionInput input = extractionInput(runId);
            if (SENSITIVE.matcher(input.message()).find()) { finishJob(runId, "SKIPPED", "消息可能包含敏感凭据，未提取记忆"); return; }
            if (sourceSuppressed(input)) { finishJob(runId, "SKIPPED", "来源已被用户修改或删除，不重复提取"); return; }
            MemoryGateway.Extracted result = gateway.extract(input.baseUrl(), input.modelName(), vault.decrypt(input.apiKeyCipher()), input.message());
            tx.executeWithoutResult(status -> {
                int exists = db.queryForObject("SELECT COUNT(*) FROM messages WHERE id=? AND user_id=?", Integer.class, input.messageId(), input.userId());
                if (exists == 0 || sourceSuppressed(input)) { finishJob(runId, "SKIPPED", "来源已删除或被用户抑制"); return; }
                List<String> facts = saveExtracted(input, "L1", result.facts(), List.of());
                boolean sceneDue = db.queryForObject("SELECT COUNT(*) FROM memories WHERE user_id=? AND source_session_id=? AND layer='L2' AND updated_at>?", Integer.class,
                        input.userId(), input.sessionId(), System.currentTimeMillis() - 900_000) == 0;
                List<String> scenes = facts.isEmpty() || !sceneDue ? List.of() : saveExtracted(input, "L2", result.scenes(), facts);
                int validL1 = db.queryForObject("SELECT COUNT(*) FROM memories WHERE user_id=? AND layer='L1' AND status='ACTIVE'", Integer.class, input.userId());
                if (!facts.isEmpty() && validL1 >= 50 && validL1 % 50 < facts.size()) saveExtracted(input, "L3", result.persona(), scenes.isEmpty() ? facts : scenes);
                finishJob(runId, "COMPLETED", null);
            });
            redis.invalidate(input.userId());
        } catch (Exception e) { finishJob(runId, "FAILED", e.getMessage() == null ? "记忆提取失败" : e.getMessage()); }
    }

    private List<String> saveExtracted(ExtractionInput source, String layer, List<String> values, List<String> parents) {
        List<String> ids = new ArrayList<>();
        for (String raw : values) {
            String content = raw.trim();
            if (content.isBlank() || content.length() > 2000 || SENSITIVE.matcher(content).find()) continue;
            boolean verified = content.startsWith("VERIFIED_FACT:");
            if (verified) content = content.substring("VERIFIED_FACT:".length()).trim();
            String fp = fingerprint(content);
            int suppressed = db.queryForObject("SELECT COUNT(*) FROM memory_suppressions WHERE user_id=? AND source_message_id=? AND fingerprint=?", Integer.class, source.userId(), source.messageId(), fp);
            if (suppressed > 0) continue;
            String existing = db.query("SELECT id FROM memories WHERE user_id=? AND source_message_id=? AND layer=? AND fingerprint=?", rs -> rs.next() ? rs.getString(1) : null,
                    source.userId(), source.messageId(), layer, fp);
            if (existing != null) { ids.add(existing); continue; }
            String locked = db.query("SELECT id FROM memories WHERE user_id=? AND source_message_id=? AND layer=? AND manual_locked=1 AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null, source.userId(), source.messageId(), layer);
            if (locked != null) {
                long now = System.currentTimeMillis();
                db.update("INSERT INTO memory_change_proposals(id,user_id,memory_id,proposed_content,source_json,status,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',?,?)",
                        UUID.randomUUID().toString(), source.userId(), locked, content, stringify(List.of(Map.of("type", "RUN", "id", source.runId()))), now, now);
                ids.add(locked);
                continue;
            }
            String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
            db.update("INSERT INTO memories(id,user_id,layer,content,source_session_id,source_message_id,fingerprint,manual,status,source_kind,confidence,verified,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,'ACTIVE','TURN_BLOCK',?,?,?,?)",
                    id, source.userId(), layer, content, source.sessionId(), source.messageId(), fp, verified ? 0.95 : 0.75, verified ? 1 : 0, now, now);
            db.update("INSERT OR IGNORE INTO memory_sources(user_id,memory_id,source_type,source_id,created_at) VALUES(?,?,?,?,?)",
                    source.userId(), id, "RUN", source.runId(), now);
            for (String parent : parents) db.update("INSERT OR IGNORE INTO memory_dependencies(user_id,parent_id,child_id) VALUES(?,?,?)", source.userId(), parent, id);
            ids.add(id);
        }
        return ids;
    }

    public Recall recall(String userId, String query) {
        String cacheQuery = query == null ? "" : query;
        try {
            ModelService.ModelConnection embedding = models.resolveEmbedding(userId);
            cacheQuery += "\0embedding=" + (embedding == null ? "none" : embedding.id() + ":" + embedding.version());
        } catch (Exception e) { cacheQuery += "\0embedding=unavailable"; }
        if (query != null && !query.isBlank()) {
            String cached = redis.cachedRecall(userId, cacheQuery);
            if (cached != null) {
                try {
                    Recall value = json.readValue(cached, Recall.class);
                    return new Recall(value.mode(), value.message(), value.memories(), true, value.degradedReason());
                }
                catch (JsonProcessingException e) { throw new IllegalStateException("Redis 记忆召回缓存损坏", e); }
            }
        }
        Recall result;
        java.util.concurrent.Future<Recall> future = recallWorkers.submit(() -> recallUncached(userId, query));
        try { result = future.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            List<MemoryView> active = list(userId, "", "").stream().filter(item -> item.status().equals("ACTIVE")).limit(200).toList();
            result = keyword(active, query == null ? "" : query, "长期记忆召回超过 5 秒，已跳过向量检索并降级为关键词检索");
        }
        catch (Exception e) {
            future.cancel(true);
            List<MemoryView> active = list(userId, "", "").stream().filter(item -> item.status().equals("ACTIVE")).limit(200).toList();
            result = keyword(active, query == null ? "" : query, "长期记忆召回异常，已降级为关键词检索");
        }
        if (query != null && !query.isBlank()) redis.cacheRecall(userId, cacheQuery, stringify(result));
        return result;
    }

    private Recall recallUncached(String userId, String query) {
        List<MemoryView> active = list(userId, "", "").stream().filter(item -> item.status().equals("ACTIVE")).limit(200).toList();
        if (active.isEmpty()) return new Recall("none", "暂无可召回记忆", List.of());
        if (query == null || query.isBlank()) return new Recall("none", "查询内容为空，未召回记忆", List.of());
        if (query.length() > 8000) return keyword(active, query.substring(0, 8000), "查询内容过长，使用截断后的关键词检索");
        try {
            ModelService.ModelConnection model = models.resolveEmbedding(userId);
            if (model == null) return keyword(active, query, "未设置默认嵌入模型，使用关键词检索");
            List<String> inputs = new ArrayList<>(); inputs.add(query);
            List<MemoryView> missing = new ArrayList<>();
            for (MemoryView memory : active) if (cached(memory, model) == null) { missing.add(memory); inputs.add(memory.content()); }
            List<double[]> vectors = gateway.embeddings(model, inputs);
            double[] queryVector = vectors.get(0);
            for (int i = 0; i < missing.size(); i++) {
                double[] vector = vectors.get(i + 1);
                if (vector.length != queryVector.length) throw new IllegalStateException("嵌入向量维度不一致");
                MemoryView memory = missing.get(i);
                db.update("INSERT OR REPLACE INTO memory_embeddings(memory_id,model_id,model_version,memory_version,dimensions,vector_json) VALUES(?,?,?,?,?,?)",
                        memory.id(), model.id(), model.version(), memory.version(), vector.length, stringify(vector));
            }
            List<Scored> scored = new ArrayList<>();
            for (MemoryView memory : active) {
                double[] vector = cached(memory, model);
                if (vector == null || vector.length != queryVector.length) throw new IllegalStateException("嵌入索引维度不一致");
                scored.add(new Scored(memory, cosine(queryVector, vector)));
            }
            List<MemoryView> result = scored.stream().filter(item -> item.score() > 0.1).sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(5).map(Scored::memory).toList();
            return new Recall("embedding", "使用已配置嵌入模型检索", result);
        } catch (Exception e) { return keyword(active, query, "嵌入检索不可用，使用关键词检索：" + (e.getMessage() == null ? "服务异常" : e.getMessage())); }
    }

    private Recall keyword(List<MemoryView> items, String query, String message) {
        Set<String> terms = new LinkedHashSet<>();
        for (String part : query.toLowerCase(java.util.Locale.ROOT).split("[\\s\\p{Punct}\\p{P}]+")) {
            if (part.isBlank()) continue;
            terms.add(part);
            int[] chars = part.codePoints().toArray();
            for (int i = 0; i + 1 < chars.length; i++) terms.add(new String(chars, i, 2));
        }
        List<Scored> scored = items.stream().map(item -> {
            String content = item.content().toLowerCase(java.util.Locale.ROOT);
            long score = terms.stream().filter(content::contains).count();
            return new Scored(item, score + (item.layer().equals("L3") ? 0.1 : 0));
        }).filter(item -> item.score() > 0).sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(5).toList();
        return new Recall("keyword", message, scored.stream().map(Scored::memory).toList());
    }
    private double[] cached(MemoryView memory, ModelService.ModelConnection model) {
        String vector = db.query("SELECT vector_json FROM memory_embeddings WHERE memory_id=? AND model_id=? AND model_version=? AND memory_version=?",
                rs -> rs.next() ? rs.getString(1) : null, memory.id(), model.id(), model.version(), memory.version());
        if (vector == null) return null;
        try { return json.readValue(vector, double[].class); } catch (JsonProcessingException e) { throw new IllegalStateException("记忆向量损坏", e); }
    }
    private static double cosine(double[] a, double[] b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i]; }
        return aa == 0 || bb == 0 ? 0 : dot / Math.sqrt(aa * bb);
    }
    private ExtractionInput extractionInput(String runId) {
        List<ExtractionInput> records = db.query("SELECT j.user_id,j.session_id,j.source_message_id,m.content,c.messages_json,c.base_url,c.model_name,c.api_key_cipher "
                        + "FROM memory_jobs j JOIN messages m ON m.id=j.source_message_id AND m.user_id=j.user_id JOIN run_contexts c ON c.run_id=j.run_id WHERE j.run_id=?",
                (rs, n) -> new ExtractionInput(runId, rs.getString(1), rs.getString(2), rs.getString(3),
                        completeTurnText(runId, rs.getString(4), rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8)), runId);
        if (records.isEmpty()) throw new IllegalStateException("来源会话已删除，无法提取记忆");
        return records.get(0);
    }
    private String completeTurnText(String runId, String userMessage, String messagesJson) {
        List<Map<String, Object>> events = db.query("SELECT type,payload,created_at FROM run_events WHERE run_id=? AND type IN ('tool_started','confirmation_required','tool_result','run_completed','run_failed','run_cancelled') ORDER BY seq",
                (rs, n) -> Map.of("type", rs.getString(1), "payload", parseJson(rs.getString(2)), "createdAt", rs.getLong(3)), runId);
        return stringify(Map.of("userMessage", userMessage, "modelMessages", parseJson(messagesJson), "executionEvidence", events));
    }
    private JsonNode parseJson(String value) { try { return json.readTree(value); } catch (JsonProcessingException e) { return json.createObjectNode().put("raw", value); } }
    private void suppress(String userId, MemoryView memory) {
        if (memory.sourceMessageId() != null) {
            db.update("INSERT OR IGNORE INTO memory_suppressions(user_id,source_message_id,fingerprint) VALUES(?,?,?)", userId, memory.sourceMessageId(), fingerprint(memory.content()));
            db.update("INSERT OR IGNORE INTO memory_source_suppressions(user_id,source_message_id) VALUES(?,?)", userId, memory.sourceMessageId());
        }
    }
    private void saveRevision(String userId, MemoryView memory, String revised, String operation) {
        db.update("INSERT INTO memory_revisions(id,user_id,target_type,target_id,version,original_content,revised_content,operation,manual,created_at) VALUES(?,?,?,?,?,?,?,?,1,?)",
                UUID.randomUUID().toString(), userId, memory.layer(), memory.id(), memory.version() + 1, memory.content(), revised, operation, System.currentTimeMillis());
    }
    private MemoryView memoryView(java.sql.ResultSet rs) throws java.sql.SQLException {
        long deletedAt = rs.getLong(15);
        Long deleted = rs.wasNull() ? null : deletedAt;
        return new MemoryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                rs.getString(7), rs.getInt(8), rs.getLong(9), rs.getLong(10), rs.getString(11), rs.getDouble(12), rs.getBoolean(13), rs.getBoolean(14), deleted);
    }
    private boolean sourceSuppressed(ExtractionInput source) {
        return db.queryForObject("SELECT COUNT(*) FROM memory_source_suppressions WHERE user_id=? AND source_message_id=?", Integer.class, source.userId(), source.messageId()) > 0;
    }
    private void deleteDependents(String userId, String id) {
        List<String> ids = db.query("WITH RECURSIVE descendants(id) AS (SELECT child_id FROM memory_dependencies WHERE user_id=? AND parent_id=? UNION SELECT d.child_id FROM memory_dependencies d JOIN descendants p ON d.parent_id=p.id WHERE d.user_id=?) SELECT id FROM descendants",
                (rs, n) -> rs.getString(1), userId, id, userId);
        for (String childId : ids) {
            MemoryView child = get(userId, childId);
            suppress(userId, child);
            db.update("DELETE FROM memories WHERE id=? AND user_id=?", childId, userId);
        }
    }
    public void sourceSessionDeleted(String userId, String sessionId) {
        List<String> ids = db.query("SELECT id FROM memories WHERE user_id=? AND source_session_id=?", (rs, n) -> rs.getString(1), userId, sessionId);
        for (String id : ids) {
            MemoryView item = get(userId, id);
            suppress(userId, item);
            invalidateDependents(userId, id);
            db.update("DELETE FROM memory_embeddings WHERE memory_id=?", id);
            db.update("UPDATE memories SET status='STALE',updated_at=? WHERE id=? AND user_id=?", System.currentTimeMillis(), id, userId);
        }
        redis.invalidate(userId);
    }
    private void invalidateDependents(String userId, String id) {
        db.update("WITH RECURSIVE descendants(id) AS (SELECT child_id FROM memory_dependencies WHERE user_id=? AND parent_id=? UNION SELECT d.child_id FROM memory_dependencies d JOIN descendants p ON d.parent_id=p.id WHERE d.user_id=?) "
                        + "UPDATE memories SET status='STALE',updated_at=? WHERE user_id=? AND id IN (SELECT id FROM descendants)", userId, id, userId, System.currentTimeMillis(), userId);
        db.update("DELETE FROM memory_embeddings WHERE memory_id IN (SELECT id FROM memories WHERE user_id=? AND status='STALE')", userId);
    }
    private void finishJob(String runId, String status, String error) { db.update("UPDATE memory_jobs SET status=?,error=?,updated_at=? WHERE run_id=?", status, error, System.currentTimeMillis(), runId); }
    private void validate(MemoryInput input) {
        if (input == null || input.layer() == null || !Set.of("L1", "L2", "L3").contains(input.layer()) || input.content() == null || input.content().isBlank() || input.content().length() > 2000) throw ApiException.badRequest("记忆层级或内容无效");
        if (SENSITIVE.matcher(input.content()).find()) throw ApiException.badRequest("记忆内容可能包含敏感凭据，请移除后保存");
    }
    private String fingerprint(String value) { return vault.digest("memory", value.trim().toLowerCase(java.util.Locale.ROOT)); }
    private String stringify(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("无法保存记忆数据", e); } }
    private static String text(String value) { return value == null ? "" : value; }
    @PreDestroy public void shutdown() { workers.shutdownNow(); recallWorkers.shutdownNow(); }

    private record JobSource(String userId, String sessionId, String messageId) {}
    private record IdleJob(String runId, String userId, String sessionId, String messageId) {}
    private record ExtractionInput(String runId, String userId, String sessionId, String messageId, String message, String baseUrl, String modelName, String apiKeyCipher) {}
    private record Scored(MemoryView memory, double score) {}
    public record MemoryInput(String layer, String content, Integer expectedVersion) {
        public MemoryInput(String layer, String content) { this(layer, content, null); }
    }
    public record MemoryView(String id, String layer, String content, String sourceSessionId, String sourceMessageId, boolean manual,
                             String status, int version, long createdAt, long updatedAt, String sourceKind, double confidence,
                             boolean verified, boolean manualLocked, Long deletedAt) {}
    public record MemoryJob(String runId, String sessionId, String status, String error, int attempts, long createdAt, long updatedAt) {}
    public record MemoryJobPage(List<MemoryJob> items, int page, int size, long total, JobStats counts) {}
    public record JobStats(long queued, long running, long failed, long completed, long skipped, long interrupted, long redisPending) {}
    public record LayerCounts(long l2, long l3) {}
    public record Recall(String mode, String message, List<MemoryView> memories, boolean cacheHit, String degradedReason) {
        public Recall(String mode, String message, List<MemoryView> memories) {
            this(mode, message, memories, false,
                    mode.equals("keyword") && (message.contains("降级") || message.contains("未设置") || message.contains("超过")) ? message : null);
        }
    }
    public record HotContext(String summary, List<MemoryView> persona, List<MemoryView> scenes, long pendingJobs, boolean cacheHit,
                             String sessionSummary, LayerCounts loadedCounts, LayerCounts activeTotals, JobStats jobStats,
                             String recallMode, String degradedReason) {}
    public record RevisionView(String id, int version, String originalContent, String revisedContent, String operation, boolean manual, long createdAt) {}
    public record ProposalView(String id, String memoryId, String proposedContent, String sourceJson, String status, long createdAt, long updatedAt) {}
}
