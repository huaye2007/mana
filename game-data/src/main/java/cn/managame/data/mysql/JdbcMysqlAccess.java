package cn.managame.data.mysql;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Stateless JDBC facade. Connections and their owning DataSource remain application-owned. */
public final class JdbcMysqlAccess implements MysqlAccess {
    private static final System.Logger LOG = System.getLogger(JdbcMysqlAccess.class.getName());
    private final DataSource source;
    /** What the database is, read once from connection metadata on first need; null until then. */
    private volatile Boolean mysql;
    public JdbcMysqlAccess(DataSource source) { this.source = Objects.requireNonNull(source); }

    /**
     * True for MySQL and MariaDB; H2 and the rest keep one UPDATE per row. When the metadata cannot be read — the
     * database is down, or a DataSource does not offer it — the answer is the per-row path, which is correct on
     * any database, and is not remembered, so a later save looks again.
     */
    @Override public boolean supportsMultiRowUpdate() {
        Boolean known = mysql;
        if (known != null) return known;
        try { mysql = known = inspect(); return known; }
        catch (RuntimeException unknown) { return false; }
    }
    /**
     * Reads the database product once. On MySQL it also checks that batched INSERTs are rewritten into one
     * statement: without {@code rewriteBatchedStatements=true} Connector/J sends them row by row, several times
     * slower, and nothing else would say so.
     */
    private boolean inspect() {
        return connection("<metadata>", c -> {
            DatabaseMetaData meta = c.getMetaData();
            String product = meta.getDatabaseProductName();
            boolean result = "MySQL".equalsIgnoreCase(product) || "MariaDB".equalsIgnoreCase(product);
            if (result && !rewritesBatches(c, meta.getURL()))
                LOG.log(System.Logger.Level.WARNING, "rewriteBatchedStatements is off: Connector/J sends batched INSERTs"
                        + " row by row, several times slower; add rewriteBatchedStatements=true to the JDBC URL");
            return result;
        });
    }
    /** Asks Connector/J itself when it is the driver, so a setting made as a DataSource property counts too. */
    private static boolean rewritesBatches(Connection connection, String url) {
        try {
            ClassLoader loader = connection.getClass().getClassLoader();
            Class<?> jdbc = Class.forName("com.mysql.cj.jdbc.JdbcConnection", false, loader);
            Object properties = jdbc.getMethod("getPropertySet").invoke(connection.unwrap(jdbc));
            Object property = Class.forName("com.mysql.cj.conf.PropertySet", false, loader)
                    .getMethod("getBooleanProperty", String.class).invoke(properties, "rewriteBatchedStatements");
            return Boolean.TRUE.equals(Class.forName("com.mysql.cj.conf.RuntimeProperty", false, loader)
                    .getMethod("getValue").invoke(property));
        } catch (ReflectiveOperationException | SQLException | LinkageError | RuntimeException notConnectorJ) {
            return url != null && url.toLowerCase(Locale.ROOT).contains("rewritebatchedstatements=true");
        }
    }
    private interface SqlAction<T> { T run(Connection connection) throws SQLException; }
    private <T> T connection(String sql, SqlAction<T> action) {
        try (Connection connection = source.getConnection()) { return action.run(connection); }
        catch (SQLException e) { throw new MysqlException(sql, e); }
    }
    @Override public <T> T queryOne(String sql, Object[] args, RowMapper<T> mapper) {
        return connection(sql, c -> new Session(c).queryOne(sql, args, mapper));
    }
    @Override public <T> List<T> query(String sql, Object[] args, RowMapper<T> mapper) {
        return connection(sql, c -> new Session(c).query(sql, args, mapper));
    }
    @Override public int update(String sql, Object[] args) {
        return connection(sql, c -> new Session(c).update(sql, args));
    }
    @Override public int[] batchUpdate(String sql, List<Object[]> args) {
        if (args.isEmpty()) return new int[0];
        return connection(sql, c -> new Session(c).batchUpdate(sql, args));
    }
    /**
     * One transaction on one connection. Once commit returns the work is durable, so a failure after it —
     * restoring autoCommit, returning the connection — is logged and never thrown: reporting a committed batch
     * as failed would make the persistence pipeline retry or drop changes that are already stored.
     */
    @Override public <T> T transaction(TransactionAction<T> action) {
        Connection c;
        try { c = source.getConnection(); } catch (SQLException e) { throw new MysqlException("<transaction>", e); }
        Session session = new Session(c);
        boolean autoCommit = true, switched = false;
        T result;
        try {
            autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            switched = true;
            result = action.execute(session);
            c.commit();
        } catch (Throwable e) {
            session.active = false;
            if (switched) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                try { c.setAutoCommit(autoCommit); } catch (SQLException restore) { e.addSuppressed(restore); }
            }
            try { c.close(); } catch (SQLException close) { e.addSuppressed(close); }
            if (e instanceof RuntimeException r) throw r;
            if (e instanceof Error error) throw error;
            throw new MysqlException("<transaction>", e);
        }
        session.active = false;
        try { c.setAutoCommit(autoCommit); } catch (SQLException after) { cleanupFailed(after); }
        try { c.close(); } catch (SQLException after) { cleanupFailed(after); }
        return result;
    }
    private static void cleanupFailed(SQLException after) {
        LOG.log(System.Logger.Level.WARNING, "Connection cleanup failed after a committed transaction", after);
    }
    private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
        statement.clearParameters();
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof Character character) value = character.toString();
            if (value != null && !(value instanceof String || value instanceof byte[] || value instanceof Byte
                    || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof Boolean))
                throw new IllegalArgumentException("Unsupported JDBC argument: " + value.getClass());
            statement.setObject(i + 1, value);
        }
    }
    private static final class Session implements MysqlTransaction {
        private final Connection connection;
        private final Thread owner = Thread.currentThread();
        private boolean active = true;
        Session(Connection connection) { this.connection = connection; }
        private void check() {
            if (!active || owner != Thread.currentThread())
                throw new IllegalStateException("Transaction is only valid on its calling thread during the callback");
        }
        @Override public <T> T queryOne(String sql, Object[] args, RowMapper<T> mapper) {
            check();
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    T result = mapper.map(rows);
                    if (rows.next()) throw new SQLException("queryOne returned more than one row");
                    return result;
                }
            } catch (SQLException e) { throw new MysqlException(sql, e); }
        }
        @Override public <T> List<T> query(String sql, Object[] args, RowMapper<T> mapper) {
            check();
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, args);
                try (var rows = statement.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rows.next()) result.add(mapper.map(rows));
                    return result;
                }
            } catch (SQLException e) { throw new MysqlException(sql, e); }
        }
        @Override public int update(String sql, Object[] args) {
            check();
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, args); return statement.executeUpdate();
            } catch (SQLException e) { throw new MysqlException(sql, e); }
        }
        @Override public int[] batchUpdate(String sql, List<Object[]> args) {
            check(); if (args.isEmpty()) return new int[0];
            try (var statement = connection.prepareStatement(sql)) {
                for (Object[] values : args) { bind(statement, values); statement.addBatch(); }
                int[] counts = statement.executeBatch();
                for (int count : counts) if (count == Statement.EXECUTE_FAILED)
                    throw new SQLException("Batch contains EXECUTE_FAILED");
                return counts;
            } catch (SQLException e) { throw new MysqlException(sql, e); }
        }
    }
}
