package com.agentdesk.workbench.agent;

import com.agentdesk.workbench.mcp.McpService;
import com.agentdesk.workbench.skills.SkillService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Auditable, fail-closed first-stage router. The model still chooses parameters inside the allowed candidate set. */
@Service
public class IntentRouterService {
    private static final Set<String> MUTATIONS = Set.of("写入", "新增", "新建", "创建", "建表", "写一个表", "插入", "修改", "更新", "删除", "清理", "设置", "insert", "update", "delete", "create table", "alter table", "drop table", "truncate table", "set", "add", "remove");
    private static final Set<String> MYSQL_SIGNALS = Set.of("mysql", "sql", "数据库", "数据表", "表结构", "查询表");
    private static final Set<String> QUERY_SIGNALS = Set.of("查询", "查看", "查一下", "看一下", "看看", "有什么", "列出", "统计", "前十", "前10", "select", "show", "describe");
    private static final Set<String> CONTINUATION_SIGNALS = Set.of("继续", "重新", "再查", "再看", "前十", "前10", "这些", "这个表");
    private static final Pattern QUALIFIED_TABLE = Pattern.compile("(?i)(?:[a-z][a-z0-9_$]{0,63}\\.)?[a-z][a-z0-9_$]{0,63}_[a-z0-9_$]{1,63}");
    private static final Pattern FIELD_LIST = Pattern.compile("(?i)^\\s*(?:可以[,，:]*)?(?:`?[a-z][a-z0-9_]*`?\\s*[,，]\\s*){1,}`?[a-z][a-z0-9_]*`?(?:\\s*[,，].*)?\\s*$");
    private static final Pattern ROW_MUTATION = Pattern.compile("(?:加|添加|增加|录入|追加)\\s*\\d+\\s*条");

    public RouteDecision route(String message, String mode, List<SkillService.SkillSnapshot> skills, List<McpService.ToolView> mcpTools,
                               boolean mysqlEnabled, boolean redisEnabled) {
        return route(message, mode, skills, mcpTools, mysqlEnabled, redisEnabled, null);
    }

    public RouteDecision route(String message, String mode, List<SkillService.SkillSnapshot> skills, List<McpService.ToolView> mcpTools,
                               boolean mysqlEnabled, boolean redisEnabled, String previousIntent) {
        String text = message.toLowerCase(Locale.ROOT);
        boolean fieldListReply = FIELD_LIST.matcher(text).matches();
        boolean mutation = containsAny(text, MUTATIONS) || ROW_MUTATION.matcher(text).find()
                || ("MYSQL_MUTATE".equals(previousIntent) && fieldListReply);
        boolean tableReference = text.contains("表") || QUALIFIED_TABLE.matcher(text).find();
        boolean databaseContinuation = isMysqlIntent(previousIntent)
                && (containsAny(text, CONTINUATION_SIGNALS) || fieldListReply);
        boolean databaseRequest = containsAny(text, MYSQL_SIGNALS)
                || (tableReference && (mutation || containsAny(text, QUERY_SIGNALS)))
                || databaseContinuation;
        boolean mysql = mysqlEnabled && databaseRequest;
        boolean redis = redisEnabled && containsAny(text, Set.of("redis", "缓存", "键值", "stream", "哈希", "队列"));
        boolean skill = containsAny(text, Set.of("skill", "技能", "脚本"));
        boolean mcp = containsAny(text, Set.of("mcp", "外部工具", "工具服务"))
                || mcpTools.stream().anyMatch(tool -> text.contains(tool.name().toLowerCase(Locale.ROOT)));
        List<String> intents = new ArrayList<>();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (mysql) { intents.add(mutation ? "MYSQL_MUTATE" : "MYSQL_READ"); candidates.add("builtin-mysql"); }
        if (redis) { intents.add(mutation ? "REDIS_MUTATE" : "REDIS_READ"); candidates.add("builtin-redis"); }
        if ("BUILD".equals(mode) && skill) {
            boolean script = text.contains("脚本") || text.contains("script");
            intents.add(script ? "SKILL_SCRIPT" : "SKILL_GUIDANCE");
            skills.forEach(item -> candidates.add("skill:" + item.id()));
        }
        if (mcp && !mcpTools.isEmpty()) {
            intents.add("CHAT".equals(mode) ? "MCP_READ" : "MCP_TOOL");
            mcpTools.forEach(item -> candidates.add("mcp:" + item.serverId() + ":" + item.name()));
        }
        if (intents.size() > 1) return new RouteDecision("MULTI_STEP", List.copyOf(candidates), "RULE");
        if (intents.size() == 1) return new RouteDecision(intents.get(0), List.copyOf(candidates), "RULE");
        return new RouteDecision("CHAT_ONLY", List.of(), "RULE");
    }

    private boolean containsAny(String text, Set<String> values) {
        return values.stream().anyMatch(text::contains);
    }

    private boolean isMysqlIntent(String intent) {
        return intent != null && (intent.startsWith("MYSQL_") || intent.equals("MULTI_STEP"));
    }

    public record RouteDecision(String intent, List<String> candidates, String source) {}
}
