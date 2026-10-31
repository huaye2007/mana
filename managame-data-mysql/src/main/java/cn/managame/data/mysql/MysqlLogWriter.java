package cn.managame.data.mysql;

import cn.managame.data.EntityField;
import cn.managame.data.EntityType;
import cn.managame.data.LogWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Inserts logs into one MySQL database, each log type into its {@link Table}, or into tables split by its
 * {@link PartitionKey}: {@code <table>_<suffix>}. The application owns the base table; a partition table missing when
 * its first logs arrive is created from it with {@code CREATE TABLE IF NOT EXISTS ... LIKE}, since partitions by date
 * cannot be created ahead. A batch is one transaction, whatever tables its logs go to.
 */
public final class MysqlLogWriter implements LogWriter {
    private final DataSource dataSource;
    private final JsonCodec jsonCodec;
    private final ZoneId partitionZone;
    private final ConcurrentHashMap<Class<?>, LogTable> tables = new ConcurrentHashMap<>();

    /** For logs whose fields are all scalars, with DAY, MONTH and YEAR partitions cut in UTC. */
    public MysqlLogWriter(DataSource dataSource) { this(dataSource, null, ZoneOffset.UTC); }

    /**
     * @param jsonCodec     encodes the fields that are not scalars, or null when no log has one
     * @param partitionZone the zone DAY, MONTH and YEAR partitions are cut in
     */
    public MysqlLogWriter(DataSource dataSource, JsonCodec jsonCodec, ZoneId partitionZone) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jsonCodec = jsonCodec;
        this.partitionZone = Objects.requireNonNull(partitionZone, "partitionZone");
    }

    /** Reads the base table once; throws as {@link MysqlEntityMapper#prepare} does, and for a bad {@code @PartitionKey}. */
    @Override public void prepare(Class<?> logType) {
        LogTable table = new LogTable(EntityType.of(logType));
        Jdbc.checkColumnsExist(dataSource, table.baseTable, table.columns);
        tables.put(logType, table);
    }

    @Override public void write(Class<?> logType, List<Object> logs) throws SQLException {
        LogTable table = tables.get(logType);
        if (table == null) throw new IllegalStateException(logType.getName() + " was not prepared by this writer");
        Map<String, List<Object[]>> rowsByTable = new LinkedHashMap<>();
        for (Object log : logs) rowsByTable.computeIfAbsent(table.tableOf(log), key -> new ArrayList<>()).add(Jdbc.encodeRow(table.columns, log));
        try {
            insert(table, rowsByTable);
        } catch (SQLException e) {
            if (!isMissingTable(e)) throw e;
            // DDL commits in MySQL, so the partitions are created outside the transaction, which then runs again.
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String name : rowsByTable.keySet())
                    if (!name.equals(table.baseTable))
                        statement.execute("CREATE TABLE IF NOT EXISTS " + MysqlColumn.quote(name) + " LIKE " + MysqlColumn.quote(table.baseTable));
            }
            insert(table, rowsByTable);
        }
    }

    private void insert(LogTable table, Map<String, List<Object[]>> rowsByTable) throws SQLException {
        Jdbc.inTransaction(dataSource, connection -> {
            for (Map.Entry<String, List<Object[]>> rows : rowsByTable.entrySet())
                Jdbc.executeBatch(connection, table.insertSql(rows.getKey()), rows.getValue());
        });
    }

    /** MySQL's "no such table" (1146, SQLState 42S02), anywhere in the chain. */
    private static boolean isMissingTable(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1146 || "42S02".equals(sql.getSQLState()))) return true;
        return false;
    }

    /** One log type's base table, its columns, and how a log picks its partition. */
    private final class LogTable {
        final String baseTable;
        final List<MysqlColumn> columns;
        final EntityField partitionField;
        /** Formats a time partition's suffix; null for VALUE partitions. */
        final DateTimeFormatter suffixFormat;
        final ConcurrentHashMap<String, String> insertSqlByTable = new ConcurrentHashMap<>();

        LogTable(EntityType type) {
            Table annotation = type.entityClass().getAnnotation(Table.class);
            if (annotation == null) throw new IllegalArgumentException(type + " needs @Table to be written to MySQL");
            baseTable = MysqlColumn.checkName(annotation.name());
            columns = Jdbc.columns(type, jsonCodec);
            List<EntityField> partitionFields = type.fields().stream().filter(field -> field.field().isAnnotationPresent(PartitionKey.class)).toList();
            if (partitionFields.size() > 1) throw new IllegalArgumentException(type + " has more than one @PartitionKey");
            partitionField = partitionFields.isEmpty() ? null : partitionFields.getFirst();
            PartitionType partitionType = partitionField == null ? PartitionType.VALUE : partitionField.field().getAnnotation(PartitionKey.class).type();
            if (partitionField != null) {
                Class<?> fieldType = EntityType.boxed(partitionField.type());
                boolean valid = partitionType == PartitionType.VALUE
                        ? fieldType == String.class || fieldType == Long.class || fieldType == Integer.class || fieldType == Short.class || fieldType == Byte.class
                        : fieldType == Long.class;
                if (!valid) throw new IllegalArgumentException("@PartitionKey " + partitionField.field()
                        + " must be a long of epoch milliseconds for " + partitionType + ", or integral or String for VALUE");
            }
            suffixFormat = switch (partitionType) {
                case DAY -> DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT).withZone(partitionZone);
                case MONTH -> DateTimeFormatter.ofPattern("uuuuMM", Locale.ROOT).withZone(partitionZone);
                case YEAR -> DateTimeFormatter.ofPattern("uuuu", Locale.ROOT).withZone(partitionZone);
                case VALUE -> null;
            };
        }

        /** The table a log goes to; IllegalArgumentException for a partition value that makes no table name. */
        String tableOf(Object log) {
            if (partitionField == null) return baseTable;
            Object value = Objects.requireNonNull(partitionField.get(log), "@PartitionKey");
            String suffix = suffixFormat == null ? value.toString() : suffixFormat.format(Instant.ofEpochMilli((Long) value));
            for (int i = 0; i < suffix.length(); i++) {
                char c = suffix.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_'))
                    throw new IllegalArgumentException("partition value " + suffix + " makes no table name");
            }
            return MysqlColumn.checkName(baseTable + "_" + suffix);
        }

        String insertSql(String table) { return insertSqlByTable.computeIfAbsent(table, name -> Jdbc.insertSql(name, columns)); }
    }
}
