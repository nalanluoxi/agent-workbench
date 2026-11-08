package com.agentdesk.workbench.models;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/models")
public class ModelController {
    private final ModelService models;
    public ModelController(ModelService models) { this.models = models; }

    @GetMapping public List<ModelService.ModelView> list(HttpServletRequest request) { return models.list(AuthFilter.require(request).userId()); }
    @GetMapping("/{id}") public ModelService.ModelView get(HttpServletRequest request, @PathVariable String id) { return models.get(AuthFilter.require(request).userId(), id); }
    @PostMapping("/test") public ModelService.ModelTestResult test(HttpServletRequest request, @RequestBody ModelService.ModelTestRequest input) { return models.test(AuthFilter.require(request).userId(), input); }
    @PostMapping public ModelService.ModelView create(HttpServletRequest request, @RequestBody ModelService.ModelInput input) { return models.createVerified(AuthFilter.require(request).userId(), input); }
    @PutMapping("/{id}") public ModelService.ModelView update(HttpServletRequest request, @PathVariable String id, @RequestBody ModelService.ModelInput input) { return models.updateVerified(AuthFilter.require(request).userId(), id, input); }
    @DeleteMapping("/{id}") public Map<String,String> delete(HttpServletRequest request, @PathVariable String id) { models.delete(AuthFilter.require(request).userId(), id); return Map.of("message", "已删除"); }
    @PostMapping("/{id}/default") public ModelService.ModelView setDefault(HttpServletRequest request, @PathVariable String id) { return models.setDefault(AuthFilter.require(request).userId(), id); }
}
