package com.agentdesk.workbench.memory;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class MemoryController {
    private final MemoryService memory;
    public MemoryController(MemoryService memory) { this.memory = memory; }
    @GetMapping("/memories") public List<MemoryService.MemoryView> list(HttpServletRequest req, @RequestParam(defaultValue = "") String layer, @RequestParam(defaultValue = "") String search) { return memory.list(AuthFilter.require(req).userId(), layer, search); }
    @PostMapping("/memories") public MemoryService.MemoryView create(HttpServletRequest req, @RequestBody MemoryService.MemoryInput input) { return memory.create(AuthFilter.require(req).userId(), input); }
    @GetMapping("/memories/{id}") public MemoryService.MemoryView get(HttpServletRequest req, @PathVariable String id) { return memory.get(AuthFilter.require(req).userId(), id); }
    @PutMapping("/memories/{id}") public MemoryService.MemoryView update(HttpServletRequest req, @PathVariable String id, @RequestBody MemoryService.MemoryInput input) { return memory.update(AuthFilter.require(req).userId(), id, input); }
    @DeleteMapping("/memories/{id}") public Map<String,String> delete(HttpServletRequest req, @PathVariable String id) { memory.delete(AuthFilter.require(req).userId(), id); return Map.of("message", "已删除"); }
    @GetMapping("/memory-jobs") public List<MemoryService.MemoryJob> jobs(HttpServletRequest req) { return memory.jobs(AuthFilter.require(req).userId()); }
    @PostMapping("/memory-jobs/{id}/retry") public Map<String,String> retry(HttpServletRequest req, @PathVariable String id) { memory.retry(AuthFilter.require(req).userId(), id); return Map.of("message", "已重新排队"); }
    @GetMapping("/sessions/{id}/memory-context") public MemoryService.HotContext context(HttpServletRequest req, @PathVariable String id) { return memory.context(AuthFilter.require(req).userId(), id); }
}
