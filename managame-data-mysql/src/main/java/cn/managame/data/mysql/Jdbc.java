package cn.managame.data.mysql;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;

import cn.managame.data.DataException;
import cn.managame.data.spi.EntityType;
import cn.managame.data.spi.RetryableFailures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/** What the MySQL entity mapper and log writer both do with JDBC. */
final class Jdbc {
    /**
     * MySQL errors that say what the server cannot do now and nothing about the rows: the disk or a table full (1021,
     * 1114), out of memory or resources (1037, 1038, 1041), the user's connections or resources used up (1203, 1226),
     * read-only, as a primary is once another took over (1290, 1792, 1836), and a statement killed (1317).
     */
    private static final Set<Integer> SERVER_CANNOT_NOW = Set.of(1021, 1037, 1038, 1041, 1114, 1203, 1226, 1290, 1317, 1792, 1836);

    private Jdbc() {}

    /**
     * Whether a write that failed may succeed when tried again: what {@link RetryableFailures} finds, a lost connection,
     * a timeout, a deadlock, or, anywhere in the causes, a MySQL error that says what the server cannot do now. Split
     * into single rows and given up on, as a failure of the data is, a whole round's changes would be lost to a server
     * that only went read-only.
     */
    static boolean isRetryable(Throwable failure) {
        if (RetryableFailures.isRetryable(failure)) return true;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> causes = new ArrayDeque<>();
        causes.add(failure);
        while (!causes.isEmpty()) {
            Throwable cause = causes.poll();
            if (!seen.add(cause)) continue;
            if (cause instanceof SQLException sql) {
                if (SERVER_CANNOT_NOW.contains(sql.getErrorCode())) return true;
                if (sql.getNextException() != null) causes.add(sql.getNextException());
            }
            if (cause.getCause() != null) causes.add(cause.getCause());
            Collections.addAll(causes, cause.getSuppressed());
        }
        return false;
    }

    interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    /** Runs {@code work} in one transaction, rolled back when it fails. */
    static void inTransaction(DataSource dataSource, SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    // The connection is broken; the pool discards it, and the failure that matters is already thrown.
                }
            }
        }
    }

    /** Runs {@code sql} once for each row, as one JDBC batch. */
    static void executeBatch(Connection connection, String sql, List<Object[]> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) statement.setObject(i + 1, row[i]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** A column for each saved field; IllegalArgumentException when two have the same name. */
    static List<MysqlColumn> columns(EntityType type, JsonCodec jsonCodec) {
        List<MysqlColumn> columns = type.fields().stream().map(field -> new MysqlColumn(field, jsonCodec)).toList();
        Set<String> names = new HashSet<>();
        for (MysqlColumn column : columns)
            if (!names.add(column.name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException(type + " has two columns named " + column.name);
        return columns;
    }

    static String columnList(List<MysqlColumn> columns) {
        return columns.stream().map(MysqlColumn::quotedName).collect(Collectors.joining(", "));
    }

    static String insertSql(String table, List<MysqlColumn> columns) {
        return "INSERT INTO " + MysqlColumn.quote(table) + " (" + columnList(columns) + ") VALUES ("
                + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
    }

    /** The column values of {@code entity}, in the columns' order. */
    static Object[] encodeRow(List<MysqlColumn> columns, Object entity) {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < row.length; i++) row[i] = columns.get(i).encode(entity);
        return row;
    }

    /** Reads no row, but fails when the table or a column does not exist, so a mapping that does not fit fails the build. */
    static void checkColumnsExist(DataSource dataSource, String table, List<MysqlColumn> columns) {
        String sql = "SELECT " + columnList(columns) + " FROM " + MysqlColumn.quote(table) + " WHERE 1 = 0";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeQuery().close();
        } catch (SQLException e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot read table " + table + ": " + sql, e);
        }
    }
}
