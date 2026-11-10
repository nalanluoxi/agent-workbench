package com.agentdesk.workbench.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MysqlExecutorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void preparesMultiRowInsertWithBoundParameters() throws Exception {
        var operation = new OperationPolicy.Operation("INSERT", json.readTree("""
                {"table":"nalan_test","columns":["name","data"],"values":[["a","1"],["b","2"]]}
                """), true);

        MysqlExecutor.SqlQuery query = new MysqlExecutor().prepare(operation);

        assertThat(query.sql()).isEqualTo("INSERT INTO `nalan_test` (`name`,`data`) VALUES (?,?),(?,?)");
        assertThat(query.parameters()).extracting(node -> node.asText()).containsExactly("a", "1", "b", "2");
    }

    @Test void previewsInsertRowsAndRenderedSqlBeforeConfirmation() throws Exception {
        var operation = new OperationPolicy.Operation("INSERT", json.readTree("""
                {"table":"nalan_test","columns":["name","data"],"values":[["a","1"],["b","2"]]}
                """), true);

        Map<String, Object> impact = new MysqlExecutor().previewImpact(null, operation);

        assertThat(impact.get("estimatedRows")).isEqualTo(2L);
        assertThat((List<?>) impact.get("previewRows")).hasSize(2);
        assertThat(impact.get("sql")).isEqualTo("INSERT INTO `nalan_test` (`name`,`data`) VALUES ('a','1'),('b','2')");
    }

    @Test void countsValuesRowsForRawInsertSql() throws Exception {
        var operation = new OperationPolicy.Operation("SQL", json.readTree("""
                {"sql":"INSERT INTO nalan_test(name,data) VALUES ('a','x'),('b','y')"}
                """), true);

        Map<String, Object> impact = new MysqlExecutor().previewImpact(null, operation);

        assertThat(impact.get("estimatedRows")).isEqualTo(2L);
        assertThat(impact.get("target")).isEqualTo("nalan_test");
    }
}
