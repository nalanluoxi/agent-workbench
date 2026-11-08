package com.agentdesk.workbench.sessions;

import com.agentdesk.workbench.agent.ModelGateway;
import com.agentdesk.workbench.models.ModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MDIP-style asynchronous title naming: summarize after the first three turns, then refresh periodically; manual rename locks it. */
@Service
public class SessionTitleService {
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
            prompt.addObject().put("role", "system").put("content", "请根据对话主题生成一个简短中文会话标题。只返回标题文本，不要引号、标点解释或换行，最多 20 个汉字。");
            messages.stream().filter(item -> "user".equals(item.role()) || "assistant".equals(item.role())).skip(Math.max(0, messages.size() - 10))
                    .forEach(item -> prompt.addObject().put("role", item.role()).put("content", item.content()));
            String title = gateway.complete(model.baseUrl(), model.modelName(), model.apiKey(), prompt, "CHAT", java.util.Set.of(), java.util.Set.of(), ignored -> {})
                    .content().replaceAll("[\\r\\n\\\"'`]+", " ").trim();
            if (title.length() > 20) title = title.substring(0, 20).trim();
            if (!title.isBlank()) sessions.autoRename(userId, sessionId, title, periodicSummary);
        } catch (Exception ignored) { /* naming must never block or fail the conversation */ }
    }
}
