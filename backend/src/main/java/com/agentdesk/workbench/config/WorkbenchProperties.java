package com.agentdesk.workbench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workbench")
public record WorkbenchProperties(
        String dataDir, String keyFile, String authWhitelist,
        String smtpHost, int smtpPort, String smtpUser, String smtpPassword, String cliDir,
        String memoryRedisHost, int memoryRedisPort, String memoryRedisPassword, int memoryRedisDatabase,
        boolean defaultAdminEnabled, String defaultAdminEmail, String defaultAdminPassword) {
}
