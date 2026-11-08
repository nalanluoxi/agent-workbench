package com.agentdesk.workbench.sessions;

import com.agentdesk.workbench.agent.ModelGateway;
import com.agentdesk.workbench.models.ModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MDIP-style asynchronous title naming: summarize after the first three turns, then refresh periodically; manual rename locks it. */
@Service
public class SessionTitleService {
    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);
    private final SessionService sessions;
    private final ModelService models;
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();

    public SessionTitleService(SessionService sessions, ModelService models, ModelGateway gateway, ObjectMapper json) {
        this.sessions = sessions; this.models = models; this.gateway = gateway; this.json = json;
    }

    public void schedule(String userId, String sessionId) { workers.execute(() -> maybeRename(userId, sessionId)); }

    private void maybeRename(String userId, String sessionId) {
        try {
            SessionService.SessionView current = sessions.get(userId, sessionId);
            List<SessionService.MessageView> messages = sessions.messages(userId, sessionId);
            long userMessages = messages.stream().filter(item -> "user".equals(item.role())).count();
            boolean firstSummary = userMessages >= 3 && userMessages < 10 && "新会话".equals(current.title());
            boolean periodicSummary = userMessages > 3 && userMessages % 10 == 0;
            if (!firstSummary && !periodicSummary) return;
            ModelService.ModelConnection model = models.resolveChat(userId, null);
            ArrayNode prompt = json.createArrayNode();
            prompt.addObject().put("role", "system").put("content", "你是会话标题生成器。根据用户最早三次提问概括核心主题，只输出一个 4 至 16 个汉字的中文名词短语。禁止回答问题，禁止使用引号、句号、冒号、换行或任何解释。");
            messages.stream().filter(item -> "user".equals(item.role())).limit(3)
                    .forEach(item -> prompt.addObject().put("role", item.role()).put("content", item.content()));
            String title = gateway.complete(model.baseUrl(), model.modelName(), model.apiKey(), prompt, "CHAT", java.util.Set.of(), java.util.Set.of(), ignored -> {})
                    .content().replaceAll("[\\r\\n\\\"'`]+", " ").trim();
            if (title.length() > 20) title = title.substring(0, 20).trim();
            if (!title.isBlank()) sessions.autoRename(userId, sessionId, title, periodicSummary);
        } catch (Exception e) {
            log.warn("会话自动命名失败 sessionId={} reason={}", sessionId, e.getMessage());
        }
    }
}
