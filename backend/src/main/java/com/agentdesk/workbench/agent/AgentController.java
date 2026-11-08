package com.agentdesk.workbench.agent;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class AgentController {
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "interrupted", "cancelled");
    private final AgentService agent;
    private final ExecutorService streams = Executors.newCachedThreadPool();
    public AgentController(AgentService agent) { this.agent = agent; }

    @PostMapping("/sessions/{sessionId}/runs")
    public AgentService.RunView start(HttpServletRequest request, @PathVariable String sessionId, @RequestBody AgentService.StartRequest input) {
        return agent.start(AuthFilter.require(request).userId(), sessionId, input);
    }
    @GetMapping("/sessions/{sessionId}/runs") public List<AgentService.RunView> list(HttpServletRequest request, @PathVariable String sessionId) { return agent.list(AuthFilter.require(request).userId(), sessionId); }
    @GetMapping("/runs/{id}") public AgentService.RunView get(HttpServletRequest request, @PathVariable String id) { return agent.get(AuthFilter.require(request).userId(), id); }
    @GetMapping("/runs/{id}/events") public List<AgentService.EventView> events(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "0") long after) { return agent.events(AuthFilter.require(request).userId(), id, after); }
    @PostMapping("/runs/{id}/cancel") public AgentService.RunView cancel(HttpServletRequest request, @PathVariable String id) { return agent.cancel(AuthFilter.require(request).userId(), id); }

    @GetMapping(value = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        String userId = AuthFilter.require(request).userId();
        agent.get(userId, id);
        SseEmitter emitter = new SseEmitter(120_000L);
        streams.execute(() -> streamEvents(emitter, userId, id, after));
        return emitter;
    }

    private void streamEvents(SseEmitter emitter, String userId, String id, long after) {
        long last = after, deadline = System.currentTimeMillis() + 110_000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                List<AgentService.EventView> batch = agent.events(userId, id, last);
                for (AgentService.EventView event : batch) {
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(event.payload(), MediaType.APPLICATION_JSON));
                    last = event.seq();
                }
                if (TERMINAL.contains(agent.get(userId, id).status()) && batch.size() < 500) break;
                Thread.sleep(250);
            }
            emitter.complete();
        } catch (IOException | IllegalStateException ignored) { emitter.complete(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); emitter.complete(); }
    }
    @PreDestroy public void shutdown() { streams.shutdownNow(); }
}
