package com.agentdesk.workbench.auth;

public record Principal(String userId, String kind, String tokenHash, String scopeRunId, String role) {
    public Principal(String userId, String kind, String tokenHash) { this(userId, kind, tokenHash, null, "USER"); }
    public Principal(String userId, String kind, String tokenHash, String scopeRunId) { this(userId, kind, tokenHash, scopeRunId, "USER"); }
    public boolean canApprove() { return "USER".equals(kind); }
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
