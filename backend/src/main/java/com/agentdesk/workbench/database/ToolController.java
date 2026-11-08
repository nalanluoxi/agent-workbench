package com.agentdesk.workbench.database;

import com.agentdesk.workbench.auth.AuthFilter;
import com.agentdesk.workbench.core.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolController {
    private final ConnectionService connections;

    public ToolController(ConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping
    public List<ToolView> list(HttpServletRequest request) {
        return connections.list(AuthFilter.require(request).userId()).stream().map(this::view).toList();
    }

    @PostMapping("/{id}/enabled")
    public ToolView enabled(HttpServletRequest request, @PathVariable String id, @RequestBody EnabledInput input) {
        if (input == null || input.enabled() == null) throw ApiException.badRequest("缺少启用状态");
        return view(connections.setEnabled(AuthFilter.require(request).userId(), id, input.enabled()));
    }

    private ToolView view(ConnectionService.ConnectionView item) {
        List<String> chat = item.type().equals("MYSQL") ? OperationPolicy.MYSQL_READS.stream().sorted().toList() : OperationPolicy.REDIS_READS.stream().sorted().toList();
        List<String> writes = item.type().equals("MYSQL") ? OperationPolicy.MYSQL_WRITES.stream().sorted().toList() : OperationPolicy.REDIS_WRITES.stream().sorted().toList();
        List<String> build = java.util.stream.Stream.concat(chat.stream(), writes.stream()).sorted().toList();
        return new ToolView(item.id(), item.name(), item.type(), item.configured(), item.enabled(), item.host(), item.port(), item.database(),
                chat, build, writes, item.type().equals("MYSQL") ? "agentdesk-mysql" : "agentdesk-redis");
    }

    public record EnabledInput(Boolean enabled) {}
    public record ToolView(String id, String name, String type, boolean configured, boolean enabled, String host, int port,
                           String defaultDatabase, List<String> chatAllowlist, List<String> buildAllowlist,
                           List<String> confirmationList, String cliCommand) {}
}
