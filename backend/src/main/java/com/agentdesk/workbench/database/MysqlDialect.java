package com.agentdesk.workbench.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/** Detects the target engine once per connection and centralizes version-specific SQL decisions. */
public record MysqlDialect(String product, int major, int minor) {
    public static MysqlDialect detect(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String product = meta.getDatabaseProductName();
        return new MysqlDialect(product == null ? "Unknown" : product, meta.getDatabaseMajorVersion(), meta.getDatabaseMinorVersion());
    }

    public boolean isMariaDb() { return product.toLowerCase(Locale.ROOT).contains("mariadb"); }
    public boolean supportsWindowFunctions() { return isMariaDb() ? major >= 10 && minor >= 2 : major >= 8; }
    public boolean supportsCte() { return isMariaDb() ? major >= 10 && minor >= 2 : major >= 8; }
    public String limitClause(int limit) { return " LIMIT " + Math.max(1, Math.min(limit, 500)); }
    public String promptDescription() {
        return product + " " + major + "." + minor + "; CTE=" + supportsCte()
                + "; windowFunctions=" + supportsWindowFunctions() + "; use only validated common MySQL SQL";
    }
}
