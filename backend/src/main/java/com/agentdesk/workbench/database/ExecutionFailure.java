package com.agentdesk.workbench.database;

public class ExecutionFailure extends RuntimeException {
    private final boolean unknown;
    public ExecutionFailure(String message, boolean unknown, Throwable cause) { super(message, cause); this.unknown = unknown; }
    public boolean unknown() { return unknown; }
}
