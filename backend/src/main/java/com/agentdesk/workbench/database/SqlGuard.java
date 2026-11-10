package com.agentdesk.workbench.database;

import com.agentdesk.workbench.core.ApiException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL AST safety gate. This is deliberately independent from JDBC execution so
 * the same classification can be used by Agent routing, manual SQL consoles,
 * and tests without executing a statement.
 */
@Component
public class SqlGuard {
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("mysql", "information_schema", "performance_schema", "sys");

    public GuardResult inspect(String sql, int selectLimit) {
        if (sql == null || sql.isBlank() || sql.length() > 100_000) throw ApiException.badRequest("SQL 不能为空且不能超过 100000 个字符");
        String normalized = sql.trim();
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1).trim();
        if (normalized.contains(";")) throw ApiException.badRequest("不支持多条 SQL，请一次只提交一条语句");
        final Statement statement;
        try { statement = CCJSqlParserUtil.parse(normalized); }
        catch (JSQLParserException e) { throw ApiException.badRequest("SQL 语法无法解析：" + e.getMessage()); }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.contains("--") || upper.contains("/*") || upper.contains("*/")) throw ApiException.badRequest("不允许使用 SQL 注释绕过安全校验");
        Set<String> tables = new TablesNamesFinder().getTables(statement);
        for (String table : tables) {
            String schema = table.contains(".") ? table.substring(0, table.indexOf('.')) : "";
            if (SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) throw ApiException.forbidden("禁止访问 MySQL 系统库");
        }
        if (statement instanceof Select) {
            String safeSql = appendLimitIfMissing(normalized, selectLimit);
            return new GuardResult(safeSql, "READ", false, tables.stream().toList());
        }
        if (statement instanceof Insert) return new GuardResult(normalized, "WRITE", true, tables.stream().toList());
        if (statement instanceof Update) {
            boolean noWhere = !upper.contains(" WHERE ");
            return new GuardResult(normalized, noWhere ? "DANGER" : "WRITE", true, tables.stream().toList());
        }
        if (statement instanceof Delete) {
            boolean noWhere = !upper.contains(" WHERE ");
            return new GuardResult(normalized, noWhere ? "DANGER" : "DANGER", true, tables.stream().toList());
        }
        if (statement instanceof Drop || statement instanceof Truncate) return new GuardResult(normalized, "DANGER", true, tables.stream().toList());
        if (statement instanceof CreateTable) return new GuardResult(normalized, "DANGER", true, tables.stream().toList());
        throw ApiException.badRequest("该 SQL 类型暂不支持，请使用 SELECT、INSERT、UPDATE、DELETE、CREATE TABLE、DROP 或 TRUNCATE");
    }

    private String appendLimitIfMissing(String sql, int selectLimit) {
        int limit = Math.max(1, Math.min(selectLimit, 500));
        String upper = sql.toUpperCase(Locale.ROOT);
        if (upper.matches("(?s).*\\bLIMIT\\s+\\d+(\\s*,\\s*\\d+)?\\s*$")) return sql;
        return sql + " LIMIT " + limit;
    }

    public record GuardResult(String sql, String risk, boolean write, List<String> tables) {}
}
