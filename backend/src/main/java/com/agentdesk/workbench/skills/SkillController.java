package com.agentdesk.workbench.skills;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillService skills;
    public SkillController(SkillService skills) { this.skills = skills; }
    @GetMapping public List<SkillService.SkillView> list(HttpServletRequest request) { return skills.list(AuthFilter.require(request).userId()); }
    @GetMapping("/{id}") public SkillService.SkillDetail detail(HttpServletRequest request, @PathVariable String id) { return skills.detail(AuthFilter.require(request).userId(), id); }
    @PostMapping("/install/local") public SkillService.SkillDetail local(HttpServletRequest request, @RequestBody LocalInput input) { return skills.installLocal(AuthFilter.require(request).userId(), input.path()); }
    @PostMapping(value = "/install/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) public SkillService.SkillDetail zip(HttpServletRequest request, @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "") String subpath) { return skills.installZip(AuthFilter.require(request).userId(), file, subpath); }
    @PostMapping("/install/github") public SkillService.SkillDetail github(HttpServletRequest request, @RequestBody SkillService.GithubInput input) { return skills.installGithub(AuthFilter.require(request).userId(), input); }
    @PostMapping("/{id}/refresh") public SkillService.SkillDetail refresh(HttpServletRequest request, @PathVariable String id) { return skills.refreshGithub(AuthFilter.require(request).userId(), id); }
    @PutMapping("/{id}") public SkillService.SkillDetail update(HttpServletRequest request, @PathVariable String id, @RequestBody SkillService.UpdateInput input) { return skills.update(AuthFilter.require(request).userId(), id, input); }
    @PostMapping("/{id}/enabled") public SkillService.SkillDetail enabled(HttpServletRequest request, @PathVariable String id, @RequestBody EnabledInput input) { return skills.setEnabled(AuthFilter.require(request).userId(), id, input.enabled()); }
    @DeleteMapping("/{id}") public Map<String,String> delete(HttpServletRequest request, @PathVariable String id) { skills.delete(AuthFilter.require(request).userId(), id); return Map.of("message", "已卸载"); }
    public record LocalInput(String path) {}
    public record EnabledInput(boolean enabled) {}
}
