package com.agentdesk.workbench.database;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkflowController {
    private final WorkflowService workflows;
    private final WorkflowDefinition definition;
    public WorkflowController(WorkflowService workflows, WorkflowDefinition definition) { this.workflows = workflows; this.definition = definition; }
    @GetMapping("/workflow-definitions/database") public WorkflowDefinition.Definition databaseDefinition(HttpServletRequest request) { AuthFilter.require(request); return definition.current(); }
    @PostMapping("/database/operations") public WorkflowService.JobView create(HttpServletRequest request, @RequestBody OperationPolicy.OperationRequest input) { return workflows.create(AuthFilter.require(request), input); }
    @GetMapping("/workflow-jobs/{id}") public WorkflowService.JobView get(HttpServletRequest request, @PathVariable String id) { return workflows.getJob(AuthFilter.require(request), id); }
    @GetMapping("/workflow-jobs/{id}/events") public List<WorkflowService.EventView> events(HttpServletRequest request, @PathVariable String id, @RequestParam(defaultValue = "0") long after) { workflows.getJob(AuthFilter.require(request), id); return workflows.events(AuthFilter.require(request).userId(), id, after); }
    @GetMapping("/workflow-jobs/{id}/definition") public WorkflowDefinition.Definition jobDefinition(HttpServletRequest request, @PathVariable String id) { return workflows.getDefinition(AuthFilter.require(request), id); }
    @GetMapping("/actions/{id}") public WorkflowService.ActionView action(HttpServletRequest request, @PathVariable String id) { var principal = AuthFilter.require(request); var action = workflows.getAction(principal.userId(), id); workflows.getJob(principal, action.jobId()); return action; }
    @PostMapping("/actions/{id}/approve") public WorkflowService.JobView approve(HttpServletRequest request, @PathVariable String id) { return workflows.approve(AuthFilter.require(request), id); }
    @PostMapping("/actions/{id}/reject") public WorkflowService.JobView reject(HttpServletRequest request, @PathVariable String id) { return workflows.reject(AuthFilter.require(request), id); }
}
