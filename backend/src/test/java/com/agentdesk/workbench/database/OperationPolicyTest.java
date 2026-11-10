package com.agentdesk.workbench.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OperationPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OperationPolicy policy = new OperationPolicy(new SqlGuard());

    @Test void selectSqlIsReadOnlyAndDoesNotRequireConfirmation() throws Exception {
        var request = new OperationPolicy.OperationRequest("builtin-mysql", "SQL", json.readTree("{\"sql\":\"SELECT * FROM crawler\"}"), null);
        var operation = policy.prepare("MYSQL", request, "CHAT");
        assertThat(operation.write()).isFalse();
        assertThat(operation.args().path("risk").asText()).isEqualTo("READ");
    }

    @Test void updateAndDeleteSqlAreWritesThatRequireConfirmation() throws Exception {
        var update = policy.prepare("MYSQL", new OperationPolicy.OperationRequest("builtin-mysql", "SQL",
                json.readTree("{\"sql\":\"UPDATE crawler SET status='done' WHERE id=1\"}"), null), "BUILD");
        var delete = policy.prepare("MYSQL", new OperationPolicy.OperationRequest("builtin-mysql", "SQL",
                json.readTree("{\"sql\":\"DELETE FROM crawler WHERE id=1\"}"), null), "BUILD");
        assertThat(update.write()).isTrue();
        assertThat(delete.write()).isTrue();
    }

    @Test void createTableRequiresBuildConfirmationAndChatRejectsIt() throws Exception {
        var request = new OperationPolicy.OperationRequest("builtin-mysql", "SQL",
                json.readTree("{\"sql\":\"CREATE TABLE crawler.test_crawler (id BIGINT PRIMARY KEY)\"}"), null);

        var operation = policy.prepare("MYSQL", request, "BUILD");

        assertThat(operation.write()).isTrue();
        assertThat(operation.args().path("risk").asText()).isEqualTo("DANGER");
        assertThatThrownBy(() -> policy.prepare("MYSQL", request, "CHAT")).hasMessageContaining("只允许 MySQL 只读 SQL");
    }

    @Test void acceptsBoundedMultiRowInsertAndStillClassifiesItAsWrite() throws Exception {
        var request = new OperationPolicy.OperationRequest("builtin-mysql", "INSERT", json.readTree("""
                {"database":"crawler","table":"nalan_test","columns":["name","time","data"],
                 "values":[["test_01","2024-01-01 10:00:00","data_01"],["test_02","2024-01-02 10:00:00","data_02"]]}
                """), null);

        var operation = policy.prepare("MYSQL", request, "BUILD");

        assertThat(operation.write()).isTrue();
        assertThat(operation.command()).isEqualTo("INSERT");
        assertThatThrownBy(() -> policy.prepare("MYSQL", request, "CHAT")).hasMessageContaining("只允许 MySQL 只读操作");
    }

    @Test void rejectsMultiRowInsertWithMismatchedColumnCount() throws Exception {
        var request = new OperationPolicy.OperationRequest("builtin-mysql", "INSERT", json.readTree("""
                {"table":"nalan_test","columns":["name","data"],"values":[["only-one-value"]]}
                """), null);

        assertThatThrownBy(() -> policy.prepare("MYSQL", request, "BUILD")).hasMessageContaining("columns 数量一致");
    }
}
