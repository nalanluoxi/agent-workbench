package com.agentdesk.workbench.config;

import com.agentdesk.workbench.core.SecretVault;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final WorkbenchProperties properties;
    private final DataSource dataSource;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public DatabaseInitializer(WorkbenchProperties properties, DataSource dataSource, SecretVault vault) {
        this.properties = properties;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate db = new JdbcTemplate(dataSource);
        Integer roleColumn = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('users') WHERE name='role'", Integer.class);
        if (roleColumn == null || roleColumn == 0) db.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'USER' CHECK(role IN ('USER','ADMIN'))");
        Integer contextColumn = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('run_contexts') WHERE name='pending_calls_json'", Integer.class);
        if (contextColumn == null || contextColumn == 0) db.execute("ALTER TABLE run_contexts ADD COLUMN pending_calls_json TEXT");
        Integer skillsColumn = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('run_contexts') WHERE name='skills_json'", Integer.class);
        if (skillsColumn == null || skillsColumn == 0) db.execute("ALTER TABLE run_contexts ADD COLUMN skills_json TEXT NOT NULL DEFAULT '[]'");
        Integer sourceColumn = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('run_contexts') WHERE name='source_message_id'", Integer.class);
        if (sourceColumn == null || sourceColumn == 0) db.execute("ALTER TABLE run_contexts ADD COLUMN source_message_id TEXT");
        Integer mcpColumn = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('run_contexts') WHERE name='mcp_tools_json'", Integer.class);
        if (mcpColumn == null || mcpColumn == 0) db.execute("ALTER TABLE run_contexts ADD COLUMN mcp_tools_json TEXT NOT NULL DEFAULT '[]'");
        Integer workflowSnapshot = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('workflow_jobs') WHERE name='workflow_snapshot'", Integer.class);
        if (workflowSnapshot == null || workflowSnapshot == 0) db.execute("ALTER TABLE workflow_jobs ADD COLUMN workflow_snapshot TEXT");
        Integer mcpBundle = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('mcp_servers') WHERE name='bundle_hash'", Integer.class);
        if (mcpBundle == null || mcpBundle == 0) db.execute("ALTER TABLE mcp_servers ADD COLUMN bundle_hash TEXT");
        Integer mcpReadOnly = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('mcp_tools') WHERE name='read_only'", Integer.class);
        if (mcpReadOnly == null || mcpReadOnly == 0) db.execute("ALTER TABLE mcp_tools ADD COLUMN read_only INTEGER NOT NULL DEFAULT 0");
        Integer runMode = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('runs') WHERE name='mode'", Integer.class);
        if (runMode == null || runMode == 0) db.execute("ALTER TABLE runs ADD COLUMN mode TEXT NOT NULL DEFAULT 'CHAT' CHECK(mode IN ('CHAT','BUILD'))");
        Integer messageMode = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('messages') WHERE name='mode'", Integer.class);
        if (messageMode == null || messageMode == 0) db.execute("ALTER TABLE messages ADD COLUMN mode TEXT NOT NULL DEFAULT 'CHAT' CHECK(mode IN ('CHAT','BUILD'))");
        Integer titleLocked = db.queryForObject("SELECT COUNT(*) FROM pragma_table_info('sessions') WHERE name='title_locked'", Integer.class);
        if (titleLocked == null || titleLocked == 0) db.execute("ALTER TABLE sessions ADD COLUMN title_locked INTEGER NOT NULL DEFAULT 0");
        long now = System.currentTimeMillis();
        db.update("DELETE FROM model_test_tokens WHERE expires_at<=?", now);
        db.update("INSERT OR IGNORE INTO builtin_tool_settings(user_id,tool_id,enabled,updated_at) SELECT id,'builtin-mysql',1,? FROM users", now);
        db.update("INSERT OR IGNORE INTO builtin_tool_settings(user_id,tool_id,enabled,updated_at) SELECT id,'builtin-redis',1,? FROM users", now);
        db.update("UPDATE workflow_jobs SET status='UNKNOWN',error='服务中断，写入结果待核查',updated_at=? WHERE id IN (SELECT job_id FROM db_actions WHERE status='EXECUTING')", now);
        db.update("UPDATE db_actions SET status='UNKNOWN',error='服务中断，写入结果待核查',updated_at=? WHERE status='EXECUTING'", now);
        db.update("UPDATE workflow_jobs SET status='UNKNOWN',error='服务中断，MCP 调用结果待核查',updated_at=? WHERE id IN (SELECT job_id FROM mcp_actions WHERE status='EXECUTING')", now);
        db.update("UPDATE mcp_actions SET status='UNKNOWN',error='服务中断，MCP 调用结果待核查',updated_at=? WHERE status='EXECUTING'", now);
        db.update("UPDATE workflow_jobs SET status='UNKNOWN',error='服务中断，脚本执行结果待核查',updated_at=? WHERE id IN (SELECT job_id FROM skill_script_actions WHERE status='EXECUTING')", now);
        db.update("UPDATE skill_script_actions SET status='UNKNOWN',error='服务中断，脚本执行结果待核查',updated_at=? WHERE status='EXECUTING'", now);
        initializeDefaultAdmin(db);
    }

    private void initializeDefaultAdmin(JdbcTemplate db) {
        log.info("defaultAdminEnabled={}", properties.defaultAdminEnabled());
        if (!properties.defaultAdminEnabled()) return;
        String email = properties.defaultAdminEmail() == null ? "" : properties.defaultAdminEmail().trim().toLowerCase(Locale.ROOT);
        String password = properties.defaultAdminPassword();
        if (email.isBlank() || password == null || password.isBlank()) {
            log.error("默认管理员配置不完整，已跳过初始化");
            return;
        }
        Integer existing = db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email);
        if (existing != null && existing > 0) {
            db.update("INSERT OR IGNORE INTO user_settings(user_id) SELECT id FROM users WHERE email=?", email);
            log.info("默认管理员邮箱已存在，保留原密码和角色：{}", email);
            return;
        }
        String id = UUID.nameUUIDFromBytes(("agent-workbench-default-admin:" + email).getBytes(StandardCharsets.UTF_8)).toString();
        long now = System.currentTimeMillis();
        db.update("INSERT INTO users(id,email,password_hash,role,created_at) VALUES(?,?,?,'ADMIN',?)", id, email, passwords.encode(password), now);
        db.update("INSERT OR IGNORE INTO user_settings(user_id) VALUES(?)", id);
        log.warn("已创建本机演示管理员 {}；当前默认密码强度较低，请勿用于公网或生产环境", email);
    }
}
