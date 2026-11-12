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

    @Test void redisDiagnosticCommandsAreReadOnlyInChatAndBuild() throws Exception {
        for (String command : OperationPolicy.REDIS_READS) {
            String args = switch (command) {
                case "SCAN" -> "{\"database\":0,\"pattern\":\"agent:*\",\"limit\":1000,\"cursor\":\"0\"}";
                case "DBSIZE" -> "{\"database\":0}";
                case "HGET" -> "{\"key\":\"hash\",\"field\":\"field\"}";
                case "HGETALL", "SMEMBERS" -> "{\"key\":\"key\",\"cursor\":\"0\"}";
                case "LRANGE", "ZRANGE" -> "{\"key\":\"key\",\"start\":0,\"stop\":10}";
                default -> "{\"key\":\"key\"}";
            };
            var request = new OperationPolicy.OperationRequest("builtin-redis", command, json.readTree(args), null);
            assertThat(policy.prepare("REDIS", request, "CHAT").write()).as(command).isFalse();
            assertThat(policy.prepare("REDIS", request, "BUILD").write()).as(command).isFalse();
        }
    }

    @Test void redisScanRejectsUnsafeArguments() throws Exception {
        assertThatThrownBy(() -> policy.prepare("REDIS", new OperationPolicy.OperationRequest("builtin-redis", "SCAN",
                json.readTree("{\"limit\":1001}"), null), "CHAT")).hasMessageContaining("1 到 1000");
        assertThatThrownBy(() -> policy.prepare("REDIS", new OperationPolicy.OperationRequest("builtin-redis", "SCAN",
                json.readTree("{\"pattern\":\"\"}"), null), "CHAT")).hasMessageContaining("pattern");
        assertThatThrownBy(() -> policy.prepare("REDIS", new OperationPolicy.OperationRequest("builtin-redis", "SCAN",
                json.readTree("{\"unexpected\":true}"), null), "CHAT")).hasMessageContaining("不支持的操作参数");
    }
}
