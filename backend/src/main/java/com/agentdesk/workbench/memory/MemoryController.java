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
    private final HybridMemoryService hybrid;
    public MemoryController(MemoryService memory, HybridMemoryService hybrid) { this.memory = memory; this.hybrid = hybrid; }
    @GetMapping("/memories") public List<MemoryService.MemoryView> list(HttpServletRequest req, @RequestParam(defaultValue = "") String layer, @RequestParam(defaultValue = "") String search) { return memory.list(AuthFilter.require(req).userId(), layer, search); }
    @PostMapping("/memories") public MemoryService.MemoryView create(HttpServletRequest req, @RequestBody MemoryService.MemoryInput input) { return memory.create(AuthFilter.require(req).userId(), input); }
    @GetMapping("/memories/{id}") public MemoryService.MemoryView get(HttpServletRequest req, @PathVariable String id) { return memory.get(AuthFilter.require(req).userId(), id); }
    @PutMapping("/memories/{id}") public MemoryService.MemoryView update(HttpServletRequest req, @PathVariable String id, @RequestBody MemoryService.MemoryInput input) { return memory.update(AuthFilter.require(req).userId(), id, input); }
    @DeleteMapping("/memories/{id}") public Map<String,String> delete(HttpServletRequest req, @PathVariable String id) { memory.delete(AuthFilter.require(req).userId(), id); return Map.of("message", "已删除"); }
    @GetMapping("/memory-summaries") public List<HybridMemoryService.SummaryView> summaries(HttpServletRequest req,
            @RequestParam(required = false) String sessionId) { return hybrid.summaries(AuthFilter.require(req).userId(), sessionId); }
    @GetMapping("/sessions/{id}/turn-blocks") public List<HybridMemoryService.TurnBlock> evidence(HttpServletRequest req, @PathVariable String id) { return hybrid.evidence(AuthFilter.require(req).userId(), id); }
    @GetMapping("/memories/{type}/{id}") public Object managedGet(HttpServletRequest req, @PathVariable String type, @PathVariable String id) {
        String userId = AuthFilter.require(req).userId();
        if (type.equalsIgnoreCase("L0")) return hybrid.evidenceByRun(userId, id);
        if (type.equalsIgnoreCase("SUMMARY")) return hybrid.summaryById(userId, id);
        return memory.get(userId, id);
    }
    @PatchMapping("/memories/{type}/{id}") public Object managedUpdate(HttpServletRequest req, @PathVariable String type, @PathVariable String id,
                                                                       @RequestBody ManagedInput input) {
        String userId = AuthFilter.require(req).userId();
        if (type.equalsIgnoreCase("L0")) { hybrid.reviseL0(userId, id, new HybridMemoryService.RevisionInput(input.content(), input.expectedVersion())); return hybrid.evidenceByRun(userId, id); }
        if (type.equalsIgnoreCase("SUMMARY")) return hybrid.updateSummaryManually(userId, id, new HybridMemoryService.RevisionInput(input.content(), input.expectedVersion()));
        MemoryService.MemoryView current = memory.get(userId, id);
        if (!current.layer().equalsIgnoreCase(type)) throw com.agentdesk.workbench.core.ApiException.badRequest("记忆层级与路径不一致");
        return memory.update(userId, id, new MemoryService.MemoryInput(current.layer(), input.content(), input.expectedVersion()));
    }
    @DeleteMapping("/memories/{type}/{id}") public Map<String,String> managedDelete(HttpServletRequest req, @PathVariable String type, @PathVariable String id,
                                                                                    @RequestParam(required = false) Integer expectedVersion) {
        String userId = AuthFilter.require(req).userId();
        if (type.equalsIgnoreCase("L0") || type.equalsIgnoreCase("SUMMARY")) hybrid.softDelete(userId, type, id, expectedVersion); else memory.delete(userId, id);
        return Map.of("message", "已移入回收站");
    }
    @PostMapping("/memories/{type}/{id}/restore") public Object restore(HttpServletRequest req, @PathVariable String type, @PathVariable String id) {
        String userId = AuthFilter.require(req).userId();
        if (type.equalsIgnoreCase("L0") || type.equalsIgnoreCase("SUMMARY")) { hybrid.restore(userId, type, id); return Map.of("message", "已恢复"); }
        return memory.restore(userId, id);
    }
    @GetMapping("/memories/{type}/{id}/revisions") public Object revisions(HttpServletRequest req, @PathVariable String type, @PathVariable String id) {
        String userId = AuthFilter.require(req).userId();
        return type.equalsIgnoreCase("L0") || type.equalsIgnoreCase("SUMMARY") ? hybrid.revisions(userId, type, id) : memory.revisions(userId, id);
    }
    @PostMapping("/memories/{type}/{id}/rollback") public Map<String,String> rollback(HttpServletRequest req, @PathVariable String type, @PathVariable String id,
                                                                                      @RequestBody HybridMemoryService.RollbackInput input) {
        String userId = AuthFilter.require(req).userId();
        if (type.equalsIgnoreCase("L0") || type.equalsIgnoreCase("SUMMARY")) hybrid.rollback(userId, type, id, input); else memory.rollback(userId, id, input.version());
        return Map.of("message", "已恢复指定版本");
    }
    @PostMapping("/memories/rebuild") public Map<String,Object> rebuild(HttpServletRequest req, @RequestBody HybridMemoryService.RebuildInput input) {
        String userId = AuthFilter.require(req).userId();
        return "SUMMARY".equalsIgnoreCase(input.type()) ? hybrid.rebuild(userId, input) : memory.rebuild(userId, input.type());
    }
    @GetMapping("/memory-proposals") public List<MemoryService.ProposalView> proposals(HttpServletRequest req) { return memory.proposals(AuthFilter.require(req).userId()); }
    @PostMapping("/memory-proposals/{id}/{decision:approve|reject}") public MemoryService.ProposalView proposal(HttpServletRequest req, @PathVariable String id, @PathVariable String decision) {
        return memory.decideProposal(AuthFilter.require(req).userId(), id, decision.equals("approve"));
    }
    @GetMapping("/memory-jobs") public Object jobs(HttpServletRequest req,
            @RequestParam(required = false) Integer page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        String userId = AuthFilter.require(req).userId();
        return page == null && (status == null || status.isBlank()) ? memory.jobs(userId) : memory.jobs(userId, status, page == null ? 1 : page, size);
    }
    @GetMapping("/memory-task-events") public List<HybridMemoryService.TaskEventView> taskEvents(HttpServletRequest req) { return hybrid.taskEvents(AuthFilter.require(req).userId()); }
    @PostMapping("/memory-jobs/{id}/retry") public Map<String,String> retry(HttpServletRequest req, @PathVariable String id) { memory.retry(AuthFilter.require(req).userId(), id); return Map.of("message", "已重新排队"); }
    @GetMapping("/sessions/{id}/memory-context") public MemoryService.HotContext context(HttpServletRequest req, @PathVariable String id) { return memory.context(AuthFilter.require(req).userId(), id); }
    public record ManagedInput(String content, Integer expectedVersion) {}
}
