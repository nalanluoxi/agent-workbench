package com.agentdesk.workbench.stats;

import com.agentdesk.workbench.auth.Principal;
import com.agentdesk.workbench.core.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatsService {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public StatsService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public Dashboard dashboard(Principal principal, String period, String scope) {
        String normalizedPeriod = normalizePeriod(period);
        long since = System.currentTimeMillis() - duration(normalizedPeriod).toMillis();
        boolean all = "all".equalsIgnoreCase(scope);
        if (all && !principal.isAdmin()) throw ApiException.forbidden("只有管理员可以查看全部用户统计");
        QueryScope filter = all ? new QueryScope("", new Object[]{since}) : new QueryScope(" AND user_id=?", new Object[]{since, principal.userId()});
        Overview overview = overview(filter);
        return new Dashboard(normalizedPeriod, all ? "all" : "me", since, overview,
                trends(filter, normalizedPeriod), ranking(filter), intents(filter), confirmations(filter), resources(principal, all), recent(filter));
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

    private List<TrendPoint> trends(QueryScope filter, String period) {
        List<TrendPoint> observed = db.query("SELECT date(created_at/1000,'unixepoch','localtime') day,mode,COUNT(*),"
                        + "SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END) FROM runs WHERE created_at>=?" + filter.userClause()
                        + " GROUP BY day,mode ORDER BY day,mode",
                (rs, n) -> new TrendPoint(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), filter.args());
        return completeTrends(observed, period, LocalDate.now(ZoneId.systemDefault()));
    }

    static List<TrendPoint> completeTrends(List<TrendPoint> observed, String period, LocalDate today) {
        Map<String, TrendPoint> byBucket = new LinkedHashMap<>();
        observed.forEach(point -> byBucket.put(point.day() + "\0" + point.mode(), point));
        int days = switch (period) { case "24h" -> 1; case "30d" -> 30; default -> 7; };
        LocalDate firstDay = today.minusDays(days - 1L);
        List<TrendPoint> complete = new ArrayList<>(days * 2);
        for (int offset = 0; offset < days; offset++) {
            String day = firstDay.plusDays(offset).toString();
            for (String mode : List.of("CHAT", "BUILD")) {
                complete.add(byBucket.getOrDefault(day + "\0" + mode, new TrendPoint(day, mode, 0, 0)));
            }
        }
        return complete;
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
        String sql = "SELECT w.id,w.connection_id,w.run_id,w.status,w.error,w.created_at,c.type,c.name,"
                + "COALESCE(w.operation_snapshot,d.operation_json,m.operation_json,s.operation_json),w.result_json "
                + "FROM workflow_jobs w LEFT JOIN db_connections c ON c.id=w.connection_id "
                + "LEFT JOIN db_actions d ON d.job_id=w.id LEFT JOIN mcp_actions m ON m.job_id=w.id "
                + "LEFT JOIN skill_script_actions s ON s.job_id=w.id WHERE w.created_at>=?" + filter.userClause().replace("user_id", "w.user_id")
                + " ORDER BY w.created_at DESC LIMIT 20";
        return db.query(sql, (rs, n) -> recentItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), filter.args());
    }

    private RecentItem recentItem(String id, String resource, String runId, String status, String error, long createdAt,
                                  String connectionType, String connectionName, String operationJson, String resultJson) {
        JsonNode operation = parse(operationJson);
        JsonNode args = operation.path("args");
        String toolName;
        if (resource.startsWith("mcp:")) toolName = "MCP";
        else if (resource.startsWith("skill:")) toolName = operation.path("skillName").asText("Skill Script");
        else toolName = connectionType == null ? (connectionName == null ? resource : connectionName) : connectionType + " CLI";
        String command = first(operation.path("command").asText(""), operation.path("toolName").asText(""), operation.path("script").asText(""));
        String database = args.path("database").asText("");
        String table = args.path("table").asText("");
        String key = args.path("key").asText("");
        String target = !table.isBlank() ? (database.isBlank() ? table : database + "." + table) : !key.isBlank() ? key : database;
        JsonNode result = parse(resultJson);
        String impact = first(text(operation.path("impact").path("summary")), text(result.path("affectedRows")),
                text(result.path("count")), text(result.path("returned")));
        return new RecentItem(id, resource, status, error, createdAt, runId, toolName, command, target, impact);
    }

    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) return json.createObjectNode();
        try { return json.readTree(value); }
        catch (Exception ignored) { return json.createObjectNode(); }
    }

    private String text(JsonNode value) { return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText(); }
    private String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }

    private long count(String sql, Object[] args) {
        Long value = db.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private double rate(long success, long denominator) {
        return denominator == 0 ? 0 : Math.round(success * 1000.0 / denominator) / 10.0;
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) return "7d";
        if (!Set.of("24h", "7d", "30d").contains(period)) throw ApiException.badRequest("统计周期只支持 24h、7d、30d");
        return period;
    }

    private Duration duration(String period) {
        if (period.equals("7d")) return Duration.ofDays(7);
        return switch (period) {
            case "24h" -> Duration.ofHours(24);
            case "30d" -> Duration.ofDays(30);
            default -> throw new IllegalArgumentException("unsupported period");
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
    public record RecentItem(String id, String resource, String status, String error, long createdAt, String runId,
                             String toolName, String command, String target, String impact) {}
}
