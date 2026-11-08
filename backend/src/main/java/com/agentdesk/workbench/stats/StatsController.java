package com.agentdesk.workbench.stats;

import com.agentdesk.workbench.auth.AuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
public class StatsController {
    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/dashboard")
    public StatsService.Dashboard dashboard(HttpServletRequest request,
                                            @RequestParam(defaultValue = "7d") String period,
                                            @RequestParam(defaultValue = "me") String scope) {
        return stats.dashboard(AuthFilter.require(request), period, scope);
    }
}
