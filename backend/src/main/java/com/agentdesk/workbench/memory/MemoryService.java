package com.agentdesk.workbench.memory;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import com.agentdesk.workbench.models.ModelService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemoryService {
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(password|passwd|api[ _-]?key|secret|token|密码|验证码|密钥|授权码)\\s*[:=：]\\s*\\S+|-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ModelService models;
    private final MemoryGateway gateway;
    private final SecretVault vault;
    private final ObjectMapper json;
    private final MemoryRedisStore redis;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();

    public MemoryService(JdbcTemplate db, TransactionTemplate tx, ModelService models, MemoryGateway gateway, SecretVault vault,
                         ObjectMapper json, MemoryRedisStore redis) {
        this.db = db; this.tx = tx; this.models = models; this.gateway = gateway; this.vault = vault; this.json = json; this.redis = redis;
    }

    public List<MemoryView> list(String userId, String layer, String search) {
        if (layer != null && !layer.isBlank() && !Set.of("L1", "L2", "L3").contains(layer)) throw ApiException.badRequest("记忆层级无效");
        return db.query("SELECT id,layer,content,source_session_id,source_message_id,manual,status,version,created_at,updated_at FROM memories "
                        + "WHERE user_id=? AND (?='' OR layer=?) AND (?='' OR instr(content,?)>0) ORDER BY updated_at DESC LIMIT 500",
                (rs, n) -> new MemoryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7), rs.getInt(8), rs.getLong(9), rs.getLong(10)),
                userId, text(layer), text(layer), text(search), text(search));
    }

    public MemoryView get(String userId, String id) {
        MemoryView result = db.query("SELECT id,layer,content,source_session_id,source_message_id,manual,status,version,created_at,updated_at FROM memories WHERE user_id=? AND id=?",
                rs -> rs.next() ? new MemoryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7), rs.getInt(8), rs.getLong(9), rs.getLong(10)) : null, userId, id);
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
        if (!original.layer().equals(input.layer())) throw ApiException.badRequest("不能更改记忆层级");
        tx.executeWithoutResult(status -> {
            suppress(userId, original);
            invalidateDependents(userId, id);
            db.update("DELETE FROM memory_embeddings WHERE memory_id=?", id);
            db.update("UPDATE memories SET content=?,fingerprint=?,manual=1,status='ACTIVE',version=version+1,updated_at=? WHERE id=? AND user_id=?",
                    input.content().trim(), fingerprint(input.content().trim()), System.currentTimeMillis(), id, userId);
        });
        redis.invalidate(userId);
        return get(userId, id);
    }

    public void delete(String userId, String id) {
        MemoryView original = get(userId, id);
        tx.executeWithoutResult(status -> {
            suppress(userId, original);
            deleteDependents(userId, id);
            db.update("DELETE FROM memories WHERE id=? AND user_id=?", id, userId);
        });
        redis.invalidate(userId);
    }

    public List<MemoryJob> jobs(String userId) {
        return db.query("SELECT run_id,session_id,status,error,attempts,created_at,updated_at FROM memory_jobs WHERE user_id=? ORDER BY updated_at DESC LIMIT 100",
                (rs, n) -> new MemoryJob(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7)), userId);
    }

    public void queue(String runId) {
        List<JobSource> source = db.query("SELECT r.user_id,r.session_id,c.source_message_id FROM runs r JOIN run_contexts c ON c.run_id=r.id WHERE r.id=? AND c.source_message_id IS NOT NULL",
                (rs, n) -> new JobSource(rs.getString(1), rs.getString(2), rs.getString(3)), runId);
        if (source.isEmpty()) return;
        JobSource value = source.get(0); long now = System.currentTimeMillis();
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
                String runId = redis.take(2);
                if (runId != null) extract(runId);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                throw new IllegalStateException("Redis 记忆队列不可用", e);
            }
        }
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
                List<String> scenes = facts.isEmpty() ? List.of() : saveExtracted(input, "L2", result.scenes(), facts);
                if (!facts.isEmpty()) saveExtracted(input, "L3", result.persona(), scenes.isEmpty() ? facts : scenes);
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
            String fp = fingerprint(content);
            int suppressed = db.queryForObject("SELECT COUNT(*) FROM memory_suppressions WHERE user_id=? AND source_message_id=? AND fingerprint=?", Integer.class, source.userId(), source.messageId(), fp);
            if (suppressed > 0) continue;
            String existing = db.query("SELECT id FROM memories WHERE user_id=? AND source_message_id=? AND layer=? AND fingerprint=?", rs -> rs.next() ? rs.getString(1) : null,
                    source.userId(), source.messageId(), layer, fp);
            if (existing != null) { ids.add(existing); continue; }
            String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
            db.update("INSERT INTO memories(id,user_id,layer,content,source_session_id,source_message_id,fingerprint,manual,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,'ACTIVE',?,?)",
                    id, source.userId(), layer, content, source.sessionId(), source.messageId(), fp, now, now);
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
                try { return json.readValue(cached, Recall.class); }
                catch (JsonProcessingException e) { throw new IllegalStateException("Redis 记忆召回缓存损坏", e); }
            }
        }
        Recall result = recallUncached(userId, query);
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
        List<ExtractionInput> records = db.query("SELECT j.user_id,j.session_id,j.source_message_id,m.content,c.base_url,c.model_name,c.api_key_cipher "
                        + "FROM memory_jobs j JOIN messages m ON m.id=j.source_message_id AND m.user_id=j.user_id JOIN run_contexts c ON c.run_id=j.run_id WHERE j.run_id=?",
                (rs, n) -> new ExtractionInput(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)), runId);
        if (records.isEmpty()) throw new IllegalStateException("来源会话已删除，无法提取记忆");
        return records.get(0);
    }
    private void suppress(String userId, MemoryView memory) {
        if (memory.sourceMessageId() != null) {
            db.update("INSERT OR IGNORE INTO memory_suppressions(user_id,source_message_id,fingerprint) VALUES(?,?,?)", userId, memory.sourceMessageId(), fingerprint(memory.content()));
            db.update("INSERT OR IGNORE INTO memory_source_suppressions(user_id,source_message_id) VALUES(?,?)", userId, memory.sourceMessageId());
        }
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
    @PreDestroy public void shutdown() { workers.shutdownNow(); }

    private record JobSource(String userId, String sessionId, String messageId) {}
    private record ExtractionInput(String userId, String sessionId, String messageId, String message, String baseUrl, String modelName, String apiKeyCipher) {}
    private record Scored(MemoryView memory, double score) {}
    public record MemoryInput(String layer, String content) {}
    public record MemoryView(String id, String layer, String content, String sourceSessionId, String sourceMessageId, boolean manual, String status, int version, long createdAt, long updatedAt) {}
    public record MemoryJob(String runId, String sessionId, String status, String error, int attempts, long createdAt, long updatedAt) {}
    public record Recall(String mode, String message, List<MemoryView> memories) {}
}
