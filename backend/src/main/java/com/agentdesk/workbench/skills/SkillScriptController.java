package com.agentdesk.workbench.skills;

import com.agentdesk.workbench.auth.AuthFilter;
import com.agentdesk.workbench.database.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SkillScriptController {
    private final SkillService skills;
    private final SkillScriptService scripts;
    public SkillScriptController(SkillService skills, SkillScriptService scripts) { this.skills = skills; this.scripts = scripts; }
    @GetMapping("/skills/{id}/scripts") public List<String> list(HttpServletRequest request, @PathVariable String id) { return skills.scripts(AuthFilter.require(request).userId(), id); }
    @PostMapping("/skill-script-runs") public WorkflowService.JobView request(HttpServletRequest request, @RequestBody SkillScriptService.Input input) { return scripts.request(AuthFilter.require(request), input); }
    @GetMapping("/skill-script-actions/{id}") public WorkflowService.ActionView action(HttpServletRequest request, @PathVariable String id) { return scripts.getAction(AuthFilter.require(request).userId(), id); }
    @PostMapping("/skill-script-actions/{id}/approve") public WorkflowService.JobView approve(HttpServletRequest request, @PathVariable String id) { return scripts.approve(AuthFilter.require(request), id); }
    @PostMapping("/skill-script-actions/{id}/reject") public WorkflowService.JobView reject(HttpServletRequest request, @PathVariable String id) { return scripts.reject(AuthFilter.require(request), id); }
}
