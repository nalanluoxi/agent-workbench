package com.agentdesk.workbench.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {
    @Bean
    DataSource dataSource(WorkbenchProperties properties) throws IOException {
        Path dataDir = Path.of(properties.dataDir()).toAbsolutePath().normalize();
        Path database = dataDir.resolve("workbench.db");
        Files.createDirectories(dataDir);
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:sqlite:" + database);
        pool.setDriverClassName("org.sqlite.JDBC");
        // Agent runs, SSE polling and confirmation callbacks execute on different
        // threads. A single pooled connection can therefore starve every reader
        // while an Agent transition is still being persisted.
        pool.setMaximumPoolSize(4);
        pool.setConnectionInitSql("PRAGMA foreign_keys=ON");
        pool.setPoolName("workbench-sqlite");
        return pool;
    }
}
