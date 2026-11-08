package com.agentdesk.workbench.database;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {
    private final SqlGuard guard = new SqlGuard();

    @Test void selectGetsSafeDefaultLimit() {
        var result = guard.inspect("SELECT * FROM orders", 500);
        assertThat(result.write()).isFalse();
        assertThat(result.risk()).isEqualTo("READ");
        assertThat(result.sql()).endsWith("LIMIT 500");
    }

    @Test void updateWithoutWhereIsDangerous() {
        var result = guard.inspect("UPDATE orders SET status='X'", 500);
        assertThat(result.write()).isTrue();
        assertThat(result.risk()).isEqualTo("DANGER");
    }

    @Test void multiStatementAndSystemSchemaAreRejected() {
        assertThatThrownBy(() -> guard.inspect("SELECT 1; DELETE FROM orders", 500)).hasMessageContaining("多条 SQL");
        assertThatThrownBy(() -> guard.inspect("SELECT * FROM mysql.user", 500)).hasMessageContaining("系统库");
    }
}
