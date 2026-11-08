package com.agentdesk.workbench.auth;

public interface MailSender {
    void sendCode(String recipient, String code);
}
