package cn.managame.data.mysql;
import java.sql.SQLException;
public final class MysqlException extends RuntimeException {
    private final String sql;
    public MysqlException(String sql, Throwable cause) { super("MySQL operation failed: " + sql, cause); this.sql = sql; }
    public String sql() { return sql; }
    public String sqlState() { return getCause() instanceof SQLException e ? e.getSQLState() : null; }
    public int vendorCode() { return getCause() instanceof SQLException e ? e.getErrorCode() : 0; }
}
