package com.agentdesk.workbench.stats;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatsService {
    private final JdbcTemplate db;

    public StatsService(JdbcTemplate db) {
        this.db = db;
    }

    public Dashboard dashboard(Principal principal, String period, String scope) {
        long since = System.currentTimeMillis() - duration(period).toMillis();
        boolean all = "all".equalsIgnoreCase(scope);
        if (all && !principal.isAdmin()) throw ApiException.forbidden("只有管理员可以查看全部用户统计");
        QueryScope filter = all ? new QueryScope("", new Object[]{since}) : new QueryScope(" AND user_id=?", new Object[]{since, principal.userId()});
        Overview overview = overview(filter);
        return new Dashboard(period == null ? "7d" : period, all ? "all" : "me", since, overview,
                trends(filter), ranking(filter), intents(filter), confirmations(filter), resources(principal, all), recent(filter));
    }

    private Overview overview(QueryScope filter) {
        Counts runs = db.queryForObject("SELECT COUNT(*),SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END),"
                        + "SUM(CASE WHEN status IN ('completed','failed','interrupted') THEN 1 ELSE 0 END) FROM runs WHERE created_at>=?" + filter.userClause(),
                (rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)), filter.args());
        Counts tools = db.queryForObject("SELECT COUNT(*),SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END),"
                        + "SUM(CASE WHEN status IN ('COMPLETED','FAILED','UNKNOWN') THEN 1 ELSE 0 END) FROM workflow_jobs WHERE created_at>=?" + filter.userClause(),
                (rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)), filter.args());
        long confirmations = count("SELECT COUNT(*) FROM (SELECT user_id,created_at FROM db_actions UNION ALL SELECT user_id,created_at FROM mcp_actions UNION ALL SELECT user_id,created_at FROM skill_script_actions) a WHERE created_at>=?" + filter.userClause(), filter.args());
        return new Overview(runs.total(), runs.success(), runs.denominator(), rate(runs.success(), runs.denominator()),
                tools.total(), tools.success(), tools.denominator(), rate(tools.success(), tools.denominator()), confirmations);
    }

    private List<TrendPoint> trends(QueryScope filter) {
        return db.query("SELECT date(created_at/1000,'unixepoch','localtime') day,mode,COUNT(*),"
                        + "SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END) FROM runs WHERE created_at>=?" + filter.userClause()
                        + " GROUP BY day,mode ORDER BY day,mode",
                (rs, n) -> new TrendPoint(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), filter.args());
    }

    private List<RankItem> ranking(QueryScope filter) {
        String sql = "SELECT CASE WHEN connection_id LIKE 'mcp:%' THEN 'MCP' WHEN connection_id LIKE 'skill:%' THEN 'Skill Script' "
                + "WHEN connection_id IN (SELECT id FROM db_connections WHERE type='MYSQL') THEN 'MySQL CLI' "
                + "WHEN connection_id IN (SELECT id FROM db_connections WHERE type='REDIS') THEN 'Redis CLI' ELSE connection_id END label,"
                + "COUNT(*),SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END),SUM(CASE WHEN status IN ('COMPLETED','FAILED','UNKNOWN') THEN 1 ELSE 0 END) "
                + "FROM workflow_jobs WHERE created_at>=?" + filter.userClause() + " GROUP BY label ORDER BY COUNT(*) DESC LIMIT 10";
        return db.query(sql, (rs, n) -> new RankItem(rs.getString(1), rs.getLong(2), rate(rs.getLong(3), rs.getLong(4))), filter.args());
    }

    private List<IntentItem> intents(QueryScope filter) {
        return db.query("SELECT intent,COUNT(*) FROM route_decisions WHERE created_at>=?" + filter.userClause() + " GROUP BY intent ORDER BY COUNT(*) DESC",
                (rs, n) -> new IntentItem(rs.getString(1), rs.getLong(2)), filter.args());
    }

    private Map<String, Long> confirmations(QueryScope filter) {
        String sql = "SELECT status,COUNT(*) FROM (SELECT user_id,status,created_at FROM db_actions UNION ALL SELECT user_id,status,created_at FROM mcp_actions "
                + "UNION ALL SELECT user_id,status,created_at FROM skill_script_actions) a WHERE created_at>=?" + filter.userClause() + " GROUP BY status";
        Map<String, Long> result = new LinkedHashMap<>();
        db.query(sql, rs -> { while (rs.next()) result.put(rs.getString(1), rs.getLong(2)); return null; }, filter.args());
        return result;
    }

    private ResourceSummary resources(Principal principal, boolean all) {
        String clause = all ? "" : " WHERE user_id=?";
        Object[] args = all ? new Object[]{} : new Object[]{principal.userId()};
        long configuredTools = count("SELECT COUNT(DISTINCT user_id || ':' || type) FROM db_connections" + clause, args);
        long disabledTools = count("SELECT COUNT(*) FROM builtin_tool_settings" + (all ? " WHERE enabled=0" : " WHERE enabled=0 AND user_id=?"), args);
        long skills = count("SELECT COUNT(*) FROM skills" + clause, args) + 2;
        long enabledSkills = count("SELECT COUNT(*) FROM skills" + (all ? " WHERE enabled=1" : " WHERE enabled=1 AND user_id=?"), args)
                + Math.max(0, 2 - count("SELECT COUNT(*) FROM builtin_skill_settings" + (all ? " WHERE enabled=0" : " WHERE enabled=0 AND user_id=?"), args));
        long mcp = count("SELECT COUNT(*) FROM mcp_tools" + clause, args);
        long enabledMcp = count("SELECT COUNT(*) FROM mcp_tools" + (all ? " WHERE enabled=1" : " WHERE enabled=1 AND user_id=?"), args);
        return new ResourceSummary(configuredTools, Math.max(0, configuredTools - disabledTools), skills, enabledSkills, mcp, enabledMcp);
    }

    private List<RecentItem> recent(QueryScope filter) {
        return db.query("SELECT id,connection_id,status,error,created_at FROM workflow_jobs WHERE created_at>=?" + filter.userClause() + " ORDER BY created_at DESC LIMIT 20",
                (rs, n) -> new RecentItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)), filter.args());
    }

    private long count(String sql, Object[] args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private double rate(long success, long denominator) {
        return denominator == 0 ? 0 : Math.round(success * 1000.0 / denominator) / 10.0;
    }

    private Duration duration(String period) {
        if (period == null || period.isBlank() || period.equals("7d")) return Duration.ofDays(7);
        return switch (period) {
            case "24h" -> Duration.ofHours(24);
            case "30d" -> Duration.ofDays(30);
            default -> throw ApiException.badRequest("统计周期只支持 24h、7d、30d");
        };
    }

    private record QueryScope(String userClause, Object[] args) {}
    private record Counts(long total, long success, long denominator) {}
    public record Dashboard(String period, String scope, long since, Overview overview, List<TrendPoint> trends,
                            List<RankItem> toolRanking, List<IntentItem> intents, Map<String, Long> confirmations,
                            ResourceSummary resources, List<RecentItem> recent) {}
    public record Overview(long runs, long successfulRuns, long runDenominator, double runSuccessRate,
                           long toolCalls, long successfulToolCalls, long toolDenominator, double toolSuccessRate, long confirmations) {}
    public record TrendPoint(String day, String mode, long runs, long successful) {}
    public record RankItem(String label, long calls, double successRate) {}
    public record IntentItem(String intent, long count) {}
    public record ResourceSummary(long tools, long enabledTools, long skills, long enabledSkills, long mcpTools, long enabledMcpTools) {}
    public record RecentItem(String id, String resource, String status, String error, long createdAt) {}
}
