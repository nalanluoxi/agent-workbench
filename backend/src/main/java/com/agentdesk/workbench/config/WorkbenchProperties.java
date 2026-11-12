package com.agentdesk.workbench.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "workbench")
public record WorkbenchProperties(
        String dataDir, String keyFile, String authWhitelist,
        String smtpHost, int smtpPort, String smtpUser, String smtpPassword, String cliDir,
        String memoryRedisHost, int memoryRedisPort, String memoryRedisPassword, int memoryRedisDatabase,
        boolean memoryHybridEnabled, boolean memoryPipelineDryrun,
        boolean defaultAdminEnabled, String defaultAdminEmail, String defaultAdminPassword) {
    @ConstructorBinding
    public WorkbenchProperties {}
    public WorkbenchProperties(String dataDir, String keyFile, String authWhitelist,
                               String smtpHost, int smtpPort, String smtpUser, String smtpPassword, String cliDir,
                               String memoryRedisHost, int memoryRedisPort, String memoryRedisPassword, int memoryRedisDatabase,
                               boolean defaultAdminEnabled, String defaultAdminEmail, String defaultAdminPassword) {
        this(dataDir, keyFile, authWhitelist, smtpHost, smtpPort, smtpUser, smtpPassword, cliDir,
                memoryRedisHost, memoryRedisPort, memoryRedisPassword, memoryRedisDatabase,
                false, false, defaultAdminEnabled, defaultAdminEmail, defaultAdminPassword);
    }
}
