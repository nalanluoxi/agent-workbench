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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MysqlExecutor {
    private static final int IMPACT_PREVIEW_LIMIT = 20;
    private static final Pattern SQL_TARGET = Pattern.compile("(?i)\\b(?:TABLE|INTO|UPDATE|FROM)\\s+`?([\\p{L}\\p{N}_$.-]+)`?");
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

    /** Builds a read-only impact summary before a write operation enters the confirmation state. */
    public Map<String, Object> previewImpact(ConnectionService.ConnectionConfig config, OperationPolicy.Operation operation) {
        if (!operation.write()) return Map.of();
        if (operation.command().equals("INSERT")) return insertImpact(operation);
        if (operation.command().equals("UPDATE") || operation.command().equals("DELETE")) {
            return matchedRowsImpact(config, operation);
        }
        if (operation.command().equals("SQL")) return sqlImpact(operation);
        return Map.of("scope", "DATABASE", "change", operation.command());
    }

    private Map<String, Object> insertImpact(OperationPolicy.Operation operation) {
        JsonNode args = operation.args();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (args.path("values").isObject()) rows.add(objectRow(args.path("values")));
        else {
            List<String> columns = new ArrayList<>();
            args.path("columns").forEach(column -> columns.add(column.asText()));
            args.path("values").forEach(values -> {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 0; index < columns.size(); index++) row.put(columns.get(index), plain(values.get(index)));
                rows.add(row);
            });
        }
        SqlQuery query = prepare(operation);
        return impact("ROWS", "INSERT", rows.size(), rows.stream().limit(IMPACT_PREVIEW_LIMIT).toList(),
                rows.size() > IMPACT_PREVIEW_LIMIT, render(query), args.path("where"));
    }

    private Map<String, Object> matchedRowsImpact(ConnectionService.ConnectionConfig config, OperationPolicy.Operation operation) {
        JsonNode args = operation.args();
        List<JsonNode> parameters = new ArrayList<>();
        StringBuilder predicate = new StringBuilder();
        where(predicate, args.get("where"), parameters);
        String table = quote(args.path("table").asText());
        String countSql = "SELECT COUNT(*) FROM " + table + predicate;
        String rowsSql = "SELECT * FROM " + table + predicate + " LIMIT " + IMPACT_PREVIEW_LIMIT;
        long count;
        List<Map<String, Object>> rows;
        try (Connection connection = DriverManager.getConnection(url(config), config.username(), password(config))) {
            try (PreparedStatement statement = connection.prepareStatement(countSql)) {
                statement.setQueryTimeout(10); bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) { result.next(); count = result.getLong(1); }
            }
            try (PreparedStatement statement = connection.prepareStatement(rowsSql)) {
                statement.setQueryTimeout(10); bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) { rows = readRows(result); }
            }
        } catch (SQLException e) {
            throw new ExecutionFailure("无法预估变更影响范围：" + mysqlError(e), false, e);
        }
        return impact("ROWS", operation.command(), count, rows, count > rows.size(), render(prepare(operation)), args.path("where"));
    }

    private Map<String, Object> sqlImpact(OperationPolicy.Operation operation) {
        String sql = operation.args().path("sql").asText();
        String upper = sql.stripLeading().toUpperCase(java.util.Locale.ROOT);
        String change = upper.startsWith("CREATE TABLE") ? "CREATE TABLE"
                : upper.startsWith("DROP TABLE") ? "DROP TABLE"
                : upper.startsWith("TRUNCATE TABLE") ? "TRUNCATE TABLE"
                : upper.startsWith("INSERT") ? "INSERT"
                : upper.startsWith("UPDATE") ? "UPDATE"
                : upper.startsWith("DELETE") ? "DELETE" : "SQL";
        Matcher matcher = SQL_TARGET.matcher(sql);
        String target = matcher.find() ? matcher.group(1) : "当前数据库";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", change.contains("TABLE") ? "OBJECT" : "ROWS");
        result.put("change", change);
        result.put("target", target);
        if (change.equals("INSERT")) result.put("estimatedRows", countValuesRows(sql));
        result.put("sql", sql);
        return result;
    }

    private Map<String, Object> impact(String scope, String change, long estimatedRows, List<Map<String, Object>> previewRows,
                                       boolean previewTruncated, String sql, JsonNode conditions) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("change", change);
        result.put("estimatedRows", estimatedRows);
        result.put("previewRows", previewRows);
        result.put("previewTruncated", previewTruncated);
        result.put("sql", sql);
        if (conditions != null && conditions.isObject() && !conditions.isEmpty()) result.put("conditions", conditions);
        return result;
    }

    private Object executeStatement(Connection connection, SqlQuery query, boolean write) {
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(10);
            bind(statement, query.parameters());
            if (write) return Map.of("affectedRows", statement.executeUpdate());
            try (ResultSet result = statement.executeQuery()) { return readRows(result); }
        } catch (SQLException e) {
            String state = e.getSQLState();
            boolean unknown = write && (state == null || state.startsWith("08") || e instanceof java.sql.SQLTimeoutException);
            throw new ExecutionFailure(unknown ? "执行结果未知，请核查目标数据库" : mysqlError(e), unknown, e);
        }
    }

    private void bind(PreparedStatement statement, List<JsonNode> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            JsonNode value = parameters.get(i);
            if (value.isNull()) statement.setNull(i + 1, Types.NULL);
            else if (value.isBoolean()) statement.setBoolean(i + 1, value.asBoolean());
            else if (value.isIntegralNumber()) statement.setLong(i + 1, value.asLong());
            else if (value.isFloatingPointNumber()) statement.setBigDecimal(i + 1, value.decimalValue());
            else statement.setString(i + 1, value.asText());
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
                if (args.get("values").isObject()) {
                    args.get("values").fields().forEachRemaining(entry -> { columns.add(quote(entry.getKey())); values.add(entry.getValue()); });
                    sql.append("INSERT INTO ").append(table).append(" (").append(String.join(",", columns)).append(") VALUES (")
                            .append(String.join(",", java.util.Collections.nCopies(columns.size(), "?"))).append(")");
                } else {
                    args.get("columns").forEach(column -> columns.add(quote(column.asText())));
                    List<String> rows = new ArrayList<>();
                    args.get("values").forEach(row -> {
                        rows.add("(" + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")");
                        row.forEach(values::add);
                    });
                    sql.append("INSERT INTO ").append(table).append(" (").append(String.join(",", columns)).append(") VALUES ")
                            .append(String.join(",", rows));
                }
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

    private static String url(ConnectionService.ConnectionConfig config) {
        String host = config.host().contains(":") ? "[" + config.host() + "]" : config.host();
        return "jdbc:mysql://" + host + ":" + config.port() + "/" + config.database() + "?connectTimeout=5000&socketTimeout=10000";
    }

    private static String password(ConnectionService.ConnectionConfig config) { return config.password() == null ? "" : config.password(); }

    private static Map<String, Object> objectRow(JsonNode values) {
        Map<String, Object> row = new LinkedHashMap<>();
        values.fields().forEachRemaining(entry -> row.put(entry.getKey(), plain(entry.getValue())));
        return row;
    }

    private static Object plain(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.decimalValue();
        return value.asText();
    }

    private static String render(SqlQuery query) {
        StringBuilder result = new StringBuilder();
        int parameter = 0;
        for (int index = 0; index < query.sql().length(); index++) {
            char current = query.sql().charAt(index);
            if (current == '?' && parameter < query.parameters().size()) result.append(literal(query.parameters().get(parameter++)));
            else result.append(current);
        }
        return result.toString();
    }

    private static String literal(JsonNode value) {
        if (value == null || value.isNull()) return "NULL";
        if (value.isNumber()) return value.asText();
        if (value.isBoolean()) return value.asBoolean() ? "TRUE" : "FALSE";
        return "'" + value.asText().replace("'", "''") + "'";
    }

    private static long countValuesRows(String sql) {
        int values = sql.toUpperCase(java.util.Locale.ROOT).indexOf("VALUES");
        if (values < 0) return 0;
        boolean quoted = false; char quote = 0; int depth = 0; long rows = 0;
        for (int index = values + 6; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (quoted) {
                if (current == quote && (index + 1 >= sql.length() || sql.charAt(index + 1) != quote)) quoted = false;
                else if (current == quote && index + 1 < sql.length() && sql.charAt(index + 1) == quote) index++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') { quoted = true; quote = current; continue; }
            if (current == '(') { if (depth++ == 0) rows++; }
            else if (current == ')' && depth > 0) depth--;
        }
        return rows;
    }

    private static String mysqlError(SQLException error) {
        String state = error.getSQLState() == null ? "未知" : error.getSQLState();
        String reason = state.startsWith("23") ? "数据约束冲突，请检查主键、唯一键或外键"
                : state.equals("42S02") ? "目标数据表不存在"
                : state.equals("42S22") ? "目标字段不存在"
                : state.startsWith("42") ? "SQL 语法或数据库对象权限不满足"
                : state.startsWith("22") ? "数据格式、长度或类型不符合字段要求"
                : state.startsWith("08") ? "数据库连接在执行期间中断"
                : "MySQL 执行失败，请根据错误标识检查数据库状态";
        return reason + "（SQLState=" + state + "，错误码=" + error.getErrorCode() + "）";
    }
    record SqlQuery(String sql, List<JsonNode> parameters) {}
}
