package com.agentdesk.workbench.mcp;

import com.agentdesk.workbench.auth.AuthFilter;
import com.agentdesk.workbench.database.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class McpController {
    private final McpService mcp;
    public McpController(McpService mcp) { this.mcp = mcp; }
    @GetMapping("/mcp-servers") public List<McpService.ServerView> list(HttpServletRequest req) { return mcp.list(AuthFilter.require(req).userId()); }
    @PostMapping("/mcp-servers") public McpService.ServerView create(HttpServletRequest req, @RequestBody McpService.ServerInput input) { return mcp.create(AuthFilter.require(req).userId(), input); }
    @GetMapping("/mcp-servers/{id}") public McpService.ServerView get(HttpServletRequest req, @PathVariable String id) { return mcp.get(AuthFilter.require(req).userId(), id); }
    @PutMapping("/mcp-servers/{id}") public McpService.ServerView update(HttpServletRequest req, @PathVariable String id, @RequestBody McpService.ServerInput input) { return mcp.update(AuthFilter.require(req).userId(), id, input); }
    @DeleteMapping("/mcp-servers/{id}") public Map<String,String> delete(HttpServletRequest req, @PathVariable String id) { mcp.delete(AuthFilter.require(req).userId(), id); return Map.of("message", "已移除"); }
    @PostMapping("/mcp-servers/{id}/refresh") public List<McpService.ToolView> refresh(HttpServletRequest req, @PathVariable String id) { return mcp.refresh(AuthFilter.require(req).userId(), id); }
    @GetMapping("/mcp-servers/{id}/tools") public List<McpService.ToolView> tools(HttpServletRequest req, @PathVariable String id) { return mcp.tools(AuthFilter.require(req).userId(), id); }
    @PostMapping("/mcp-servers/{id}/tools/{name}/enabled") public McpService.ToolView enabled(HttpServletRequest req, @PathVariable String id, @PathVariable String name, @RequestBody Enabled input) { return mcp.setToolEnabled(AuthFilter.require(req).userId(), id, name, input.enabled()); }
    @PostMapping("/mcp-servers/{id}/tools/{name}/read-only") public McpService.ToolView readOnly(HttpServletRequest req, @PathVariable String id, @PathVariable String name, @RequestBody ReadOnly input) { return mcp.setToolReadOnly(AuthFilter.require(req).userId(), id, name, input.readOnly()); }
    @PostMapping("/mcp-calls") public WorkflowService.JobView call(HttpServletRequest req, @RequestBody McpService.CallInput input) { return mcp.request(AuthFilter.require(req), input); }
    @GetMapping("/mcp-actions/{id}") public WorkflowService.ActionView action(HttpServletRequest req, @PathVariable String id) { return mcp.getAction(AuthFilter.require(req).userId(), id); }
    @PostMapping("/mcp-actions/{id}/approve") public WorkflowService.JobView approve(HttpServletRequest req, @PathVariable String id) { return mcp.approve(AuthFilter.require(req), id); }
    @PostMapping("/mcp-actions/{id}/reject") public WorkflowService.JobView reject(HttpServletRequest req, @PathVariable String id) { return mcp.reject(AuthFilter.require(req), id); }
    public record Enabled(boolean enabled) {}
    public record ReadOnly(boolean readOnly) {}
}
