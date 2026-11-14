package cn.managame.data.mysql;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;

import cn.managame.data.DataException;
import cn.managame.data.spi.EntityField;
import cn.managame.data.spi.EntityType;
import cn.managame.data.spi.RetryableFailures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/** What the MySQL entity mapper and log writer both do with JDBC, and what the entity mapper creates of its tables. */
final class Jdbc {
    private static final System.Logger LOG = System.getLogger("cn.managame.data.mysql");
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
    static List<MysqlColumn> columns(EntityType type, JsonCodec jsonCodec, Function<EntityField, ColumnCodec> codecs) {
        Object newInstance = type.newInstance();
        Set<EntityField> keys = new HashSet<>(type.groupKeys());
        keys.addAll(type.mapKeys());
        if (type.id() != null) keys.add(type.id());
        List<MysqlColumn> columns = type.fields().stream()
                .map(field -> new MysqlColumn(field, keys.contains(field), newInstance, jsonCodec, codecs)).toList();
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

    /**
     * Creates what an entity's table lacks, and nothing else: the table itself with its primary key, the columns of
     * fields added since it was created, and for a grouped type a unique key on its group and map key columns, unless
     * an index of the table already starts with the group columns, which every load of a group searches by. Nothing is
     * dropped or changed: a column of another type, or one no field has, stays as it is. DataException
     * ({@code DATA_LOAD_FAILED}) when the database refuses, such as an account without the right to.
     */
    static void createMissing(DataSource dataSource, String table, List<MysqlColumn> columns, MysqlColumn idColumn,
                              List<MysqlColumn> groupColumns, List<MysqlColumn> mapKeyColumns) {
        String sql = "SELECT * FROM " + MysqlColumn.quote(table);
        try (Connection connection = dataSource.getConnection()) {
            Set<String> existing = columnNames(connection, table);
            if (existing == null) {
                sql = "CREATE TABLE IF NOT EXISTS " + MysqlColumn.quote(table) + " ("
                        + columns.stream().map(column -> column.quotedName() + " " + column.definition).collect(Collectors.joining(", "))
                        + ", PRIMARY KEY (" + idColumn.quotedName() + "))";
                execute(connection, sql);
                LOG.log(System.Logger.Level.INFO, "Created table " + table);
            } else {
                for (MysqlColumn column : columns) {
                    if (existing.contains(column.name.toLowerCase(Locale.ROOT))) continue;
                    sql = "ALTER TABLE " + MysqlColumn.quote(table) + " ADD COLUMN " + column.quotedName() + " " + column.definition;
                    if (executeUnlessThere(connection, sql, 1060, "42S21"))
                        LOG.log(System.Logger.Level.INFO, "Added column " + column.name + " to table " + table);
                }
            }
            if (groupColumns.isEmpty() || hasIndexOn(connection, table, groupColumns)) return;
            List<MysqlColumn> unique = new ArrayList<>(groupColumns);
            unique.addAll(mapKeyColumns);
            String index = "uk_" + table;
            sql = "CREATE UNIQUE INDEX " + MysqlColumn.quote(index.length() > 64 ? index.substring(0, 64) : index) + " ON " + MysqlColumn.quote(table)
                    + " (" + columnList(unique) + ")";
            if (executeUnlessThere(connection, sql, 1061, "42S11"))
                LOG.log(System.Logger.Level.INFO, "Created the unique key of table " + table + " on (" + columnList(unique) + ")");
        } catch (SQLException e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot create what table " + table + " lacks: " + sql
                    + "; give the account the right to, or build the mapper with createMissing(false)", e);
        }
    }

    /**
     * Warns of a grouped type's table without an index that starts with its group columns: every load of a group then
     * reads the whole table. For a mapper that creates nothing, whose tables the application keeps.
     */
    static void warnWithoutGroupIndex(DataSource dataSource, String table, List<MysqlColumn> groupColumns) {
        if (groupColumns.isEmpty()) return;
        try (Connection connection = dataSource.getConnection()) {
            if (!hasIndexOn(connection, table, groupColumns))
                LOG.log(System.Logger.Level.WARNING, "Table " + table + " has no index that starts with (" + columnList(groupColumns)
                        + "): every load of a group reads the whole table");
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "cannot read the indexes of table " + table + ": " + e);
        }
    }

    /**
     * Warns once of a MySQL URL without {@code rewriteBatchedStatements=true}: the driver then sends a batch a
     * statement at a time, which wrote a third as many rows a second.
     */
    static void warnUnlessBatchesAreRewritten(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url != null && url.startsWith("jdbc:mysql:") && !url.contains("rewriteBatchedStatements=true"))
                LOG.log(System.Logger.Level.WARNING, "The JDBC URL has no rewriteBatchedStatements=true: batches go to MySQL a statement at a time");
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "cannot read the JDBC URL: " + e);
        }
    }

    /** The table's column names in lower case, or null when there is no such table. */
    private static Set<String> columnNames(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet none = statement.executeQuery("SELECT * FROM " + MysqlColumn.quote(table) + " WHERE 1 = 0")) {
            ResultSetMetaData metaData = none.getMetaData();
            Set<String> names = new HashSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) names.add(metaData.getColumnName(i).toLowerCase(Locale.ROOT));
            return names;
        } catch (SQLException e) {
            // MySQL's "no such table" (1146, SQLState 42S02)
            if (e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState())) return null;
            throw e;
        }
    }

    /** Whether an index of the table starts with these columns, in any order among themselves. */
    private static boolean hasIndexOn(Connection connection, String table, List<MysqlColumn> columns) throws SQLException {
        Set<String> wanted = columns.stream().map(column -> column.name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Map<String, TreeMap<Integer, String>> columnsByIndex = new HashMap<>();
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), connection.getSchema(), table, false, true)) {
            while (indexes.next()) {
                String index = indexes.getString("INDEX_NAME"), column = indexes.getString("COLUMN_NAME");
                if (index == null || column == null) continue;
                columnsByIndex.computeIfAbsent(index, name -> new TreeMap<>()).put(indexes.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }
        for (TreeMap<Integer, String> ofIndex : columnsByIndex.values())
            if (ofIndex.size() >= wanted.size() && Set.copyOf(List.copyOf(ofIndex.values()).subList(0, wanted.size())).equals(wanted)) return true;
        return false;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Executes a statement that adds a column or an index; false, doing nothing, when the database answers that it is
     * there already (MySQL's {@code errorCode}, or {@code sqlState}), as when another process added it at the same time.
     */
    private static boolean executeUnlessThere(Connection connection, String sql, int errorCode, String sqlState) throws SQLException {
        try {
            execute(connection, sql);
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == errorCode || sqlState.equals(e.getSQLState())) return false;
            throw e;
        }
    }
}
