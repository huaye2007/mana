package cn.managame.data.mysql;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Stateless JDBC facade. Connections and their owning DataSource remain application-owned. */
public final class JdbcMysqlAccess implements MysqlAccess {
    private static final System.Logger LOG = System.getLogger(JdbcMysqlAccess.class.getName());
    private final DataSource source;
    public JdbcMysqlAccess(DataSource source) { this.source = Objects.requireNonNull(source); }
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
