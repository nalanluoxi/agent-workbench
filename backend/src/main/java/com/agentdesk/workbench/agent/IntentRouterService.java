package com.agentdesk.workbench.agent;

import com.agentdesk.workbench.mcp.McpService;
import com.agentdesk.workbench.skills.SkillService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Auditable, fail-closed first-stage router. The model still chooses parameters inside the allowed candidate set. */
@Service
public class IntentRouterService {
    private static final Set<String> MUTATIONS = Set.of("写入", "新增", "插入", "修改", "更新", "删除", "清理", "设置", "insert", "update", "delete", "set", "add", "remove");

    public RouteDecision route(String message, String mode, List<SkillService.SkillSnapshot> skills, List<McpService.ToolView> mcpTools,
                               boolean mysqlEnabled, boolean redisEnabled) {
        String text = message.toLowerCase(Locale.ROOT);
        boolean mutation = containsAny(text, MUTATIONS);
        boolean mysql = mysqlEnabled && containsAny(text, Set.of("mysql", "sql", "数据库", "数据表", "表结构", "查询表"));
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

    public record RouteDecision(String intent, List<String> candidates, String source) {}
}
