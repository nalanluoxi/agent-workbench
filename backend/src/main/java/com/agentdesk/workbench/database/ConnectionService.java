package com.agentdesk.workbench.database;

import com.agentdesk.workbench.core.ApiException;
import com.agentdesk.workbench.core.SecretVault;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the two built-in database CLI tools without exposing per-database connection CRUD. */
@Service
public class ConnectionService {
    public static final String MYSQL_TOOL = "builtin-mysql";
    public static final String REDIS_TOOL = "builtin-redis";
    private final JdbcTemplate db;
    private final SecretVault vault;

    public ConnectionService(JdbcTemplate db, SecretVault vault) {
        this.db = db;
        this.vault = vault;
    }

    public List<ConnectionView> list(String userId) {
        return List.of(toolView(userId, MYSQL_TOOL), toolView(userId, REDIS_TOOL));
    }

    public ConnectionView get(String userId, String id) {
        if (MYSQL_TOOL.equals(id) || REDIS_TOOL.equals(id)) return toolView(userId, id);
        return legacyView(userId, id);
    }

    public String typeFor(String id) {
        if (MYSQL_TOOL.equals(id)) return "MYSQL";
        if (REDIS_TOOL.equals(id)) return "REDIS";
        throw ApiException.badRequest("工具 ID 必须是 builtin-mysql 或 builtin-redis");
    }

    public ConnectionConfig resolve(String userId, String id) {
        if (MYSQL_TOOL.equals(id) || REDIS_TOOL.equals(id)) return resolveTool(userId, id, null);
        return resolveLegacy(userId, id);
    }

    /** Resolves the saved connection while preserving the database selected for this operation. */
    public ConnectionConfig resolveForOperation(String userId, String id, String requestedDatabase) {
        if (MYSQL_TOOL.equals(id) || REDIS_TOOL.equals(id)) return resolveTool(userId, id, requestedDatabase);
        ConnectionConfig config = resolveLegacy(userId, id);
        if (requestedDatabase == null || requestedDatabase.isBlank()) return config;
        String database = validateDatabase(config.type(), requestedDatabase);
        return new ConnectionConfig(config.id(), config.type(), config.host(), config.port(), database,
                config.username(), config.password(), config.version());
    }

    public ConnectionConfig resolveTool(String userId, String toolId, String requestedDatabase) {
        String type = typeFor(toolId);
        requireEnabled(userId, toolId);
        List<ConnectionConfig> records = db.query("SELECT id,type,host,port,database_name,username,password_cipher,version FROM db_connections "
                        + "WHERE user_id=? AND type=? ORDER BY updated_at DESC,rowid DESC LIMIT 1",
                (rs, n) -> new ConnectionConfig(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6), vault.decrypt(rs.getString(7)), rs.getInt(8)), userId, type);
        if (records.isEmpty()) throw ApiException.badRequest(type.equals("MYSQL") ? "本机 MySQL 工具尚未注册" : "本机 Redis 工具尚未注册");
        ConnectionConfig config = records.get(0);
        if (requestedDatabase == null || requestedDatabase.isBlank()) return config;
        String database = validateDatabase(type, requestedDatabase);
        return new ConnectionConfig(config.id(), type, config.host(), config.port(), database, config.username(), config.password(), config.version());
    }

    private String validateDatabase(String type, String requestedDatabase) {
        String database = requestedDatabase.trim();
        if (type.equals("MYSQL")) OperationPolicy.identifier(database);
        else if (!database.matches("(?:[0-9]|[1-9][0-9]{1,3})")) throw ApiException.badRequest("Redis 数据库编号应为 0 到 9999");
        return database;
    }

    public void requireEnabled(String userId, String toolId) {
        typeFor(toolId);
        Integer enabled = db.query("SELECT enabled FROM builtin_tool_settings WHERE user_id=? AND tool_id=?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, toolId);
        if (enabled != null && enabled == 0) throw ApiException.forbidden("该内置工具已停用");
    }

    @Transactional
    public ConnectionView setEnabled(String userId, String toolId, boolean enabled) {
        typeFor(toolId);
        db.update("INSERT INTO builtin_tool_settings(user_id,tool_id,enabled,updated_at) VALUES(?,?,?,?) "
                        + "ON CONFLICT(user_id,tool_id) DO UPDATE SET enabled=excluded.enabled,updated_at=excluded.updated_at",
                userId, toolId, enabled ? 1 : 0, System.currentTimeMillis());
        return toolView(userId, toolId);
    }

    /** Internal bootstrap/test hook; the public API no longer exposes connection CRUD. */
    @Transactional
    public ConnectionView create(String userId, ConnectionInput input) {
        Prepared value = prepare(input);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        db.update("INSERT INTO db_connections(id,user_id,type,name,host,port,database_name,username,password_cipher,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, userId, value.type(), value.name(), value.host(), value.port(), value.database(), value.username(), vault.encrypt(value.password()), now, now);
        return legacyView(userId, id);
    }

    @Transactional
    public ConnectionView update(String userId, String id, ConnectionInput input) {
        ConnectionView old = legacyView(userId, id);
        int executing = db.queryForObject("SELECT COUNT(*) FROM db_actions WHERE user_id=? AND connection_id=? AND status='EXECUTING'", Integer.class, userId, id);
        if (executing > 0) throw ApiException.conflict("工具有正在执行的操作，暂不能修改底层配置");
        Prepared value = prepare(input);
        if (!old.type().equals(value.type())) throw ApiException.badRequest("不能更改底层工具类型");
        String cipher = value.password() == null || value.password().isBlank() ? null : vault.encrypt(value.password());
        db.update("UPDATE db_connections SET name=?,host=?,port=?,database_name=?,username=?,password_cipher=COALESCE(?,password_cipher),version=version+1,updated_at=? WHERE user_id=? AND id=?",
                value.name(), value.host(), value.port(), value.database(), value.username(), cipher, System.currentTimeMillis(), userId, id);
        return legacyView(userId, id);
    }

    private ConnectionView toolView(String userId, String toolId) {
        String type = typeFor(toolId);
        List<ConnectionView> rows = db.query("SELECT host,port,database_name,username,password_cipher IS NOT NULL,version FROM db_connections "
                        + "WHERE user_id=? AND type=? ORDER BY updated_at DESC,rowid DESC LIMIT 1",
                (rs, n) -> new ConnectionView(toolId, type, type.equals("MYSQL") ? "MySQL CLI" : "Redis CLI", rs.getString(1),
                        rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6), true, isEnabled(userId, toolId)), userId, type);
        if (!rows.isEmpty()) return rows.get(0);
        return new ConnectionView(toolId, type, type.equals("MYSQL") ? "MySQL CLI" : "Redis CLI", "未注册", 0, "", "", false, 0, false, isEnabled(userId, toolId));
    }

    private boolean isEnabled(String userId, String toolId) {
        Integer value = db.query("SELECT enabled FROM builtin_tool_settings WHERE user_id=? AND tool_id=?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, toolId);
        return value == null || value == 1;
    }

    private ConnectionView legacyView(String userId, String id) {
        List<ConnectionView> rows = db.query("SELECT id,type,name,host,port,database_name,username,password_cipher IS NOT NULL,version FROM db_connections WHERE user_id=? AND id=?",
                (rs, n) -> new ConnectionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getInt(9), true, true), userId, id);
        if (rows.isEmpty()) throw ApiException.notFound("底层工具配置不存在");
        return rows.get(0);
    }

    private ConnectionConfig resolveLegacy(String userId, String id) {
        List<ConnectionConfig> records = db.query("SELECT id,type,host,port,database_name,username,password_cipher,version FROM db_connections WHERE user_id=? AND id=?",
                (rs, n) -> new ConnectionConfig(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                        rs.getString(6), vault.decrypt(rs.getString(7)), rs.getInt(8)), userId, id);
        if (records.isEmpty()) throw ApiException.notFound("底层工具配置不存在");
        return records.get(0);
    }

    private Prepared prepare(ConnectionInput input) {
        if (input == null || !("MYSQL".equals(input.type()) || "REDIS".equals(input.type()))) throw ApiException.badRequest("工具类型必须是 MYSQL 或 REDIS");
        if (input.name() == null || input.name().isBlank() || input.name().length() > 120) throw ApiException.badRequest("配置名称无效");
        if (input.host() == null || !input.host().matches("[A-Za-z0-9.:_-]{1,255}")) throw ApiException.badRequest("连接地址无效");
        if (input.port() == null || input.port() < 1 || input.port() > 65535) throw ApiException.badRequest("连接端口无效");
        String database = input.database() == null ? "" : input.database().trim();
        if ("MYSQL".equals(input.type()) && !database.matches("[\\p{L}\\p{N}_$]{1,64}")) throw ApiException.badRequest("MySQL 默认数据库名称无效");
        if ("REDIS".equals(input.type()) && !database.matches("(?:[0-9]|[1-9][0-9]{1,3})")) throw ApiException.badRequest("Redis 数据库编号无效");
        String username = input.username() == null ? "" : input.username().trim();
        if (username.length() > 255 || username.contains("\n") || username.contains("\r")) throw ApiException.badRequest("用户名无效");
        return new Prepared(input.type().toUpperCase(Locale.ROOT), input.name().trim(), input.host().trim(), input.port(), database, username, input.password());
    }

    public record ConnectionInput(String type, String name, String host, Integer port, String database, String username, String password) {}
    private record Prepared(String type, String name, String host, int port, String database, String username, String password) {}
    public record ConnectionView(String id, String type, String name, String host, int port, String database, String username,
                                 boolean hasPassword, int version, boolean configured, boolean enabled) {}
    public record ConnectionConfig(String id, String type, String host, int port, String database, String username, String password, int version) {}
}
