package com.agentdesk.workbench.auth;

import com.agentdesk.workbench.config.WorkbenchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL = Principal.class.getName();
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final Set<String> PUBLIC = Set.of("/api/health", "/api/auth/email-codes", "/api/auth/register", "/api/auth/login");
    private final AuthService auth;
    private final WorkbenchProperties properties;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public AuthFilter(AuthService auth, WorkbenchProperties properties, JdbcTemplate db, ObjectMapper mapper) {
        this.auth = auth; this.properties = properties; this.db = db; this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || PUBLIC.contains(path)) { chain.doFilter(request, response); return; }
        String header = request.getHeader("Authorization");
        Principal principal = header != null && header.startsWith("Bearer ") ? auth.authenticate(header.substring(7).trim()) : null;
        if (principal == null) principal = whitelist(request.getHeader("X-Workbench-Client"));
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of("error", "请先登录"));
            return;
        }
        if ("AGENT".equals(principal.kind()) && !agentRouteAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of("error", "Agent 凭据无权执行此操作"));
            return;
        }
        request.setAttribute(PRINCIPAL, principal);
        chain.doFilter(request, response);
    }

    private boolean agentRouteAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ("POST".equals(request.getMethod()) && path.equals("/api/database/operations"))
                || ("GET".equals(request.getMethod()) && (path.startsWith("/api/workflow-jobs/") || path.startsWith("/api/actions/")));
    }

    private Principal whitelist(String supplied) {
        String config = properties.authWhitelist();
        boolean enabled = config != null && !config.isBlank();
        log.debug("authWhitelistEnabled={}", enabled);
        if (!enabled || supplied == null || supplied.isBlank()) return null;
        for (String entry : config.split(",")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2 || pair[0].length() < 32 || pair[1].isBlank()) continue;
            if (!MessageDigest.isEqual(pair[0].getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) continue;
            var roles = db.query("SELECT role FROM users WHERE id=?", (rs, n) -> rs.getString(1), pair[1]);
            if (roles.size() == 1) return new Principal(pair[1], "WHITELIST", null, null, roles.get(0));
        }
        return null;
    }

    public static Principal require(HttpServletRequest request) {
        return (Principal) request.getAttribute(PRINCIPAL);
    }
}
