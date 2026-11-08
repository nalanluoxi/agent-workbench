package com.agentdesk.workbench.sessions;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final SessionService sessions;
    public SessionController(SessionService sessions) { this.sessions = sessions; }
    @GetMapping public List<SessionService.SessionView> list(HttpServletRequest request) { return sessions.list(AuthFilter.require(request).userId()); }
    @PostMapping public SessionService.SessionView create(HttpServletRequest request, @RequestBody(required = false) SessionInput input) { return sessions.create(AuthFilter.require(request).userId(), input == null ? null : input.title()); }
    @GetMapping("/{id}") public SessionService.SessionView get(HttpServletRequest request, @PathVariable String id) { return sessions.get(AuthFilter.require(request).userId(), id); }
    @PatchMapping("/{id}") public SessionService.SessionView rename(HttpServletRequest request, @PathVariable String id, @RequestBody SessionInput input) { return sessions.rename(AuthFilter.require(request).userId(), id, input.title()); }
    @DeleteMapping("/{id}") public Map<String,String> delete(HttpServletRequest request, @PathVariable String id) { sessions.delete(AuthFilter.require(request).userId(), id); return Map.of("message", "已删除"); }
    @GetMapping("/{id}/messages") public List<SessionService.MessageView> messages(HttpServletRequest request, @PathVariable String id) { return sessions.messages(AuthFilter.require(request).userId(), id); }
    public record SessionInput(String title) {}
}
