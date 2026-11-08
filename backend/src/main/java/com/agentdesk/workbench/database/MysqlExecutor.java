package com.agentdesk.workbench.database;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MysqlExecutor {
    public String describeDialect(ConnectionService.ConnectionConfig config) {
        String host = config.host().contains(":") ? "[" + config.host() + "]" : config.host();
        String database = config.database() == null || config.database().isBlank() ? "" : "/" + config.database();
        String url = "jdbc:mysql://" + host + ":" + config.port() + database + "?connectTimeout=3000&socketTimeout=5000";
        try (Connection connection = DriverManager.getConnection(url, config.username(), config.password() == null ? "" : config.password())) {
            return MysqlDialect.detect(connection).promptDescription();
        } catch (SQLException e) {
            return "未能探测数据库版本；仅允许生成并执行经安全门校验的公共 MySQL SQL";
        }
    }

    public Object execute(ConnectionService.ConnectionConfig config, OperationPolicy.Operation operation) {
        String host = config.host().contains(":") ? "[" + config.host() + "]" : config.host();
        String url = "jdbc:mysql://" + host + ":" + config.port() + "/" + config.database() + "?connectTimeout=5000&socketTimeout=10000";
        try (Connection connection = DriverManager.getConnection(url, config.username(), config.password() == null ? "" : config.password())) {
            MysqlDialect dialect = MysqlDialect.detect(connection);
            SqlQuery query = prepare(operation);
            return executeStatement(connection, query, operation.write());
        } catch (ExecutionFailure e) { throw e; }
        catch (SQLException e) { throw new ExecutionFailure("无法连接 MySQL，请检查连接与权限", false, e); }
    }

    private Object executeStatement(Connection connection, SqlQuery query, boolean write) {
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(10);
            for (int i = 0; i < query.parameters().size(); i++) {
                JsonNode value = query.parameters().get(i);
                if (value.isNull()) statement.setNull(i + 1, Types.NULL);
                else if (value.isBoolean()) statement.setBoolean(i + 1, value.asBoolean());
                else if (value.isIntegralNumber()) statement.setBigDecimal(i + 1, value.decimalValue());
                else if (value.isFloatingPointNumber()) statement.setBigDecimal(i + 1, value.decimalValue());
                else statement.setString(i + 1, value.asText());
            }
            if (write) return Map.of("affectedRows", statement.executeUpdate());
            try (ResultSet result = statement.executeQuery()) { return readRows(result); }
        } catch (SQLException e) {
            String state = e.getSQLState();
            boolean unknown = write && (state == null || state.startsWith("08") || e instanceof java.sql.SQLTimeoutException);
            throw new ExecutionFailure(unknown ? "执行结果未知，请核查目标数据库" : "MySQL 操作失败，请检查数据和权限", unknown, e);
        }
    }

    private List<Map<String, Object>> readRows(ResultSet result) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = result.getMetaData();
        while (result.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = result.getObject(i);
                if (value instanceof byte[] bytes) value = java.util.Base64.getEncoder().encodeToString(bytes);
                else if (value instanceof java.util.Date) value = value.toString();
                row.put(meta.getColumnLabel(i), value);
            }
            rows.add(row);
        }
        return rows;
    }

    SqlQuery prepare(OperationPolicy.Operation operation) {
        JsonNode args = operation.args();
        String command = operation.command();
        if (command.equals("SQL")) return new SqlQuery(args.path("sql").asText(), List.of());
        if (command.equals("DATABASES")) return new SqlQuery("SHOW DATABASES", List.of());
        if (command.equals("TABLES")) return new SqlQuery("SHOW TABLES", List.of());
        String table = quote(args.path("table").asText());
        if (command.equals("DESCRIBE")) return new SqlQuery("SHOW COLUMNS FROM " + table, List.of());
        List<JsonNode> values = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        switch (command) {
            case "SELECT" -> {
                List<String> columns = new ArrayList<>();
                if (args.has("columns")) args.get("columns").forEach(column -> columns.add(column.asText().equals("*") ? "*" : quote(column.asText())));
                else columns.add("*");
                sql.append("SELECT ").append(String.join(",", columns)).append(" FROM ").append(table);
                where(sql, args.get("where"), values);
                sql.append(" LIMIT ").append(args.path("limit").asInt(100));
            }
            case "INSERT" -> {
                List<String> columns = new ArrayList<>();
                args.get("values").fields().forEachRemaining(entry -> { columns.add(quote(entry.getKey())); values.add(entry.getValue()); });
                sql.append("INSERT INTO ").append(table).append(" (").append(String.join(",", columns)).append(") VALUES (")
                        .append(String.join(",", java.util.Collections.nCopies(columns.size(), "?"))).append(")");
            }
            case "UPDATE" -> {
                List<String> assignments = new ArrayList<>();
                args.get("values").fields().forEachRemaining(entry -> { assignments.add(quote(entry.getKey()) + "=?"); values.add(entry.getValue()); });
                sql.append("UPDATE ").append(table).append(" SET ").append(String.join(",", assignments));
                where(sql, args.get("where"), values);
            }
            case "DELETE" -> { sql.append("DELETE FROM ").append(table); where(sql, args.get("where"), values); }
            default -> throw new IllegalArgumentException("unsupported operation");
        }
        return new SqlQuery(sql.toString(), values);
    }

    private void where(StringBuilder sql, JsonNode where, List<JsonNode> values) {
        if (where == null || where.isEmpty()) return;
        List<String> clauses = new ArrayList<>();
        where.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) clauses.add(quote(entry.getKey()) + " IS NULL");
            else { clauses.add(quote(entry.getKey()) + "=?"); values.add(entry.getValue()); }
        });
        sql.append(" WHERE ").append(String.join(" AND ", clauses));
    }

    private static String quote(String value) { return "`" + OperationPolicy.identifier(value) + "`"; }
    record SqlQuery(String sql, List<JsonNode> parameters) {}
}
