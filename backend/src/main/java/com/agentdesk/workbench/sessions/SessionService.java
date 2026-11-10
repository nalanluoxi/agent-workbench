package com.agentdesk.workbench.sessions;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.memory.MemoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private final JdbcTemplate db;
    private final MemoryService memory;
    private final ObjectMapper json;
    public SessionService(JdbcTemplate db, MemoryService memory, ObjectMapper json) { this.db = db; this.memory = memory; this.json = json; }

    public List<SessionView> list(String userId) {
        return db.query("SELECT id,title,created_at,updated_at FROM sessions WHERE user_id=? ORDER BY updated_at DESC,rowid DESC",
                (rs, n) -> new SessionView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), userId);
    }

    public SessionView get(String userId, String id) {
        SessionView session = db.query("SELECT id,title,created_at,updated_at FROM sessions WHERE user_id=? AND id=?",
                rs -> rs.next() ? new SessionView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) : null, userId, id);
        if (session == null) throw ApiException.notFound("会话不存在");
        return session;
    }

    private SessionView find(String userId, String id) {
        return db.queryForObject("SELECT id,title,created_at,updated_at FROM sessions WHERE user_id=? AND id=?",
                (rs, n) -> new SessionView(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), userId, id);
    }

    public SessionView create(String userId, String title) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        db.update("INSERT INTO sessions(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)", id, userId, title == null || title.isBlank() ? "新会话" : validTitle(title), now, now);
        return find(userId, id);
    }

    public SessionView rename(String userId, String id, String title) {
        if (db.update("UPDATE sessions SET title=?,title_locked=1,updated_at=? WHERE id=? AND user_id=?", validTitle(title), System.currentTimeMillis(), id, userId) == 0) throw ApiException.notFound("会话不存在");
        return find(userId, id);
    }

    public boolean autoRename(String userId, String id, String title) { return autoRename(userId, id, title, false); }

    public boolean autoRename(String userId, String id, String title, boolean compression) {
        if (title == null || title.isBlank() || title.length() > 120) return false;
        return db.update("UPDATE sessions SET title=?,updated_at=? WHERE id=? AND user_id=? AND title_locked=0 AND (?=1 OR title='新会话')", validTitle(title), System.currentTimeMillis(), id, userId, compression ? 1 : 0) > 0;
    }

    @Transactional
    public void delete(String userId, String id) {
        get(userId, id);
        int active = db.queryForObject("SELECT COUNT(*) FROM runs WHERE session_id=? AND user_id=? AND status IN ('queued','running','waiting_confirmation','cancel_requested')", Integer.class, id, userId);
        if (active > 0) throw ApiException.conflict("会话仍有进行中的任务，请先取消");
        memory.sourceSessionDeleted(userId, id);
        db.update("DELETE FROM agent_delegations WHERE run_id IN (SELECT id FROM runs WHERE session_id=? AND user_id=?)", id, userId);
        db.update("DELETE FROM route_decisions WHERE run_id IN (SELECT id FROM runs WHERE session_id=? AND user_id=?)", id, userId);
        db.update("DELETE FROM run_contexts WHERE run_id IN (SELECT id FROM runs WHERE session_id=? AND user_id=?)", id, userId);
        db.update("DELETE FROM run_events WHERE run_id IN (SELECT id FROM runs WHERE session_id=? AND user_id=?)", id, userId);
        db.update("DELETE FROM runs WHERE session_id=? AND user_id=?", id, userId);
        db.update("DELETE FROM messages WHERE session_id=? AND user_id=?", id, userId);
        db.update("DELETE FROM sessions WHERE id=? AND user_id=?", id, userId);
    }

    public List<MessageView> messages(String userId, String sessionId) {
        get(userId, sessionId);
        return db.query("SELECT id,role,content,mode,created_at FROM messages WHERE user_id=? AND session_id=? ORDER BY created_at,rowid",
                (rs, n) -> new MessageView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)), userId, sessionId);
    }

    public List<ArtifactView> artifacts(String userId, String sessionId) {
        get(userId, sessionId);
        List<ArtifactEvent> events = db.query("SELECT r.id,e.seq,e.type,e.payload,e.created_at FROM runs r JOIN run_events e ON e.run_id=r.id "
                        + "WHERE r.user_id=? AND r.session_id=? AND e.type IN ('confirmation_required','tool_result') ORDER BY e.created_at,r.created_at,e.seq",
                (rs, n) -> new ArtifactEvent(rs.getString(1), rs.getLong(2), rs.getString(3), parse(rs.getString(4)), rs.getLong(5)), userId, sessionId);
        LinkedHashMap<String, ArtifactView> timeline = new LinkedHashMap<>();
        for (ArtifactEvent event : events) {
            String jobId = event.payload().path("id").asText("");
            String id = jobId.isBlank() ? event.runId() + ":" + event.seq() : jobId;
            ArtifactView previous = timeline.get(id);
            long createdAt = previous == null ? event.createdAt() : previous.createdAt();
            timeline.put(id, new ArtifactView(id, event.runId(), event.type(), artifactKind(event.payload()), event.payload(), createdAt, event.createdAt()));
        }
        return List.copyOf(timeline.values());
    }

    public String append(String userId, String sessionId, String role, String content) {
        return append(userId, sessionId, role, content, "CHAT");
    }

    public String append(String userId, String sessionId, String role, String content, String mode) {
        get(userId, sessionId);
        String normalizedMode = "BUILD".equalsIgnoreCase(mode) ? "BUILD" : "CHAT";
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO messages(id,user_id,session_id,role,content,mode,created_at) VALUES(?,?,?,?,?,?,?)", id, userId, sessionId, role, content, normalizedMode, now);
        db.update("UPDATE sessions SET updated_at=? WHERE id=? AND user_id=?", now, sessionId, userId);
        return id;
    }

    private static String validTitle(String value) {
        if (value == null || value.isBlank() || value.length() > 120) throw ApiException.badRequest("会话名称不能为空且不能超过 120 个字符");
        return value.trim();
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法读取会话产物", e); }
    }

    private String artifactKind(JsonNode payload) {
        String actionKind = payload.path("action").path("kind").asText("");
        if (!actionKind.isBlank()) return actionKind;
        if (!payload.path("id").asText("").isBlank() || payload.path("error").asText("").contains("数据库")) return "DATABASE";
        return "TOOL";
    }

    public record SessionView(String id, String title, long createdAt, long updatedAt) {}
    public record MessageView(String id, String role, String content, String mode, long createdAt) {}
    public record ArtifactView(String id, String runId, String type, String kind, JsonNode payload, long createdAt, long updatedAt) {}
    private record ArtifactEvent(String runId, long seq, String type, JsonNode payload, long createdAt) {}
}
