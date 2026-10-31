package cn.managame.data.mysql;

import cn.managame.data.Change;
import cn.managame.data.DataOperation;
import cn.managame.data.EntityField;
import cn.managame.data.EntityMapper;
import cn.managame.data.EntityType;
import cn.managame.data.GroupKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Keeps entities in one MySQL database, each entity type in its {@link Table}, a column per saved field named after
 * the field in snake case or by {@link Column}. The application owns the tables; {@link #prepare} reads each once to
 * check that every column exists. Add {@code rewriteBatchedStatements=true} to the JDBC URL, and keep the pool larger
 * than GameData's write threads plus the loads that run at once.
 *
 * <p>Each batch is one transaction, so a batch that failed left nothing behind and can be written again or split into
 * single rows. INSERTs are folded into multi-row statements by the driver; UPDATEs are sent as statements of up to
 * {@value #MAX_ROWS_PER_UPDATE} rows, each column taking its value by id through CASE, one round trip per statement
 * instead of per row. An UPDATE that matches fewer rows than it names fails, rather than lose a change silently.
 */
public final class MysqlEntityMapper implements EntityMapper {
    public static final int MAX_ROWS_PER_UPDATE = 100;
    private static final int MAX_ROWS_PER_DELETE = 1000;
    /** MySQL takes 65,535 placeholders in one statement; every statement here stays clear of that. */
    private static final int MAX_PARAMETERS = 60_000;
    private final DataSource dataSource;
    private final JsonCodec jsonCodec;
    private final ConcurrentHashMap<EntityType, TableMapping> tables = new ConcurrentHashMap<>();

    /** For entities whose fields are all scalars. */
    public MysqlEntityMapper(DataSource dataSource) { this(dataSource, null); }

    /** @param jsonCodec encodes the fields that are not scalars, or null when no entity has one */
    public MysqlEntityMapper(DataSource dataSource, JsonCodec jsonCodec) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jsonCodec = jsonCodec;
    }

    /**
     * Builds the statements of {@code type}'s table and reads it once. IllegalArgumentException when the class has no
     * {@code @Table} or two columns of one name, IllegalStateException for a JSON field without a JsonCodec, and
     * DataException ({@code DATA_LOAD_FAILED}) when the table or a column cannot be read.
     */
    @Override public void prepare(EntityType type) {
        TableMapping mapping = new TableMapping(type);
        Jdbc.checkColumnsExist(dataSource, mapping.table, mapping.columns);
        tables.put(type, mapping);
    }

    @Override public Object load(EntityType type, Object id) throws SQLException {
        TableMapping mapping = mappingOf(type);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(mapping.selectById)) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapping.readEntity(resultSet) : null;
            }
        }
    }

    @Override public List<Object> loadGroup(EntityType type, GroupKeys key) throws SQLException {
        TableMapping mapping = mappingOf(type);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(mapping.selectByGroup)) {
            for (int i = 0; i < key.size(); i++) statement.setObject(i + 1, key.valueAt(i));
            List<Object> entities = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) entities.add(mapping.readEntity(resultSet));
            }
            return entities;
        }
    }

    /** Encodes every row before the transaction starts, so a value that cannot be encoded never touches the table. */
    @Override public void write(EntityType type, DataOperation operation, List<Change> batch) throws SQLException {
        TableMapping mapping = mappingOf(type);
        switch (operation) {
            case INSERT -> {
                List<Object[]> rows = mapping.encodeRows(mapping.columns, batch);
                Jdbc.inTransaction(dataSource, connection -> Jdbc.executeBatch(connection, mapping.insert, rows));
            }
            case UPDATE -> {
                if (mapping.updatedColumns.isEmpty()) return;
                List<Object[]> rows = mapping.encodeRows(mapping.updatedColumns, batch);
                Jdbc.inTransaction(dataSource, connection -> mapping.update(connection, batch, rows));
            }
            case DELETE -> Jdbc.inTransaction(dataSource, connection -> mapping.delete(connection, batch));
            case DELETE_INSERT -> {
                List<Object[]> rows = mapping.encodeRows(mapping.columns, batch);
                Jdbc.inTransaction(dataSource, connection -> {
                    mapping.delete(connection, batch);
                    Jdbc.executeBatch(connection, mapping.insert, rows);
                });
            }
            case LOG_INSERT -> throw new IllegalArgumentException(operation.name());
        }
    }

    private TableMapping mappingOf(EntityType type) {
        TableMapping mapping = tables.get(type);
        if (mapping == null) throw new IllegalStateException(type + " was not prepared by this mapper");
        return mapping;
    }

    /** One entity type's table: its columns and statements. */
    private final class TableMapping {
        final EntityType type;
        final String table, selectById, selectByGroup, insert;
        final List<MysqlColumn> columns, updatedColumns;
        final MysqlColumn idColumn;
        final int rowsPerUpdate;
        final ConcurrentHashMap<Integer, String> updateSqlByRows = new ConcurrentHashMap<>(), deleteSqlByRows = new ConcurrentHashMap<>();

        TableMapping(EntityType type) {
            this.type = type;
            Table annotation = type.entityClass().getAnnotation(Table.class);
            if (annotation == null) throw new IllegalArgumentException(type + " needs @Table to be kept in MySQL");
            table = MysqlColumn.checkName(annotation.name());
            columns = Jdbc.columns(type, jsonCodec);
            idColumn = columnOf(type.id());
            Set<EntityField> keys = new HashSet<>(type.groupKeys());
            keys.addAll(type.mapKeys());
            keys.add(type.id());
            updatedColumns = columns.stream().filter(column -> !keys.contains(column.field)).toList();
            String columnList = Jdbc.columnList(columns);
            selectById = "SELECT " + columnList + " FROM " + MysqlColumn.quote(table) + " WHERE " + idColumn.quotedName() + " = ?";
            selectByGroup = "SELECT " + columnList + " FROM " + MysqlColumn.quote(table) + " WHERE "
                    + type.groupKeys().stream().map(field -> columnOf(field).quotedName() + " = ?").collect(Collectors.joining(" AND "));
            insert = Jdbc.insertSql(table, columns);
            rowsPerUpdate = updatedColumns.isEmpty() ? 0
                    : Math.max(1, Math.min(MAX_ROWS_PER_UPDATE, MAX_PARAMETERS / (2 * updatedColumns.size() + 1)));
        }

        Object readEntity(ResultSet resultSet) throws SQLException {
            Object entity = type.newInstance();
            for (int i = 0; i < columns.size(); i++) columns.get(i).read(entity, resultSet, i + 1);
            return entity;
        }

        List<Object[]> encodeRows(List<MysqlColumn> encoded, List<Change> batch) {
            List<Object[]> rows = new ArrayList<>(batch.size());
            for (Change change : batch) rows.add(Jdbc.encodeRow(encoded, change.entity()));
            return rows;
        }

        /**
         * Statements of up to {@code rowsPerUpdate} rows. One that matches fewer rows than it names fails the batch: a
         * row is missing, and succeeding would lose its change. Split into single rows, the missing one is reported.
         */
        void update(Connection connection, List<Change> batch, List<Object[]> rows) throws SQLException {
            for (int from = 0; from < batch.size(); from += rowsPerUpdate) {
                int count = Math.min(rowsPerUpdate, batch.size() - from);
                String sql = updateSqlByRows.computeIfAbsent(count, this::updateSql);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    for (int column = 0; column < updatedColumns.size(); column++)
                        for (int row = from; row < from + count; row++) {
                            statement.setObject(index++, batch.get(row).id());
                            statement.setObject(index++, rows.get(row)[column]);
                        }
                    for (int row = from; row < from + count; row++) statement.setObject(index++, batch.get(row).id());
                    int matched = statement.executeUpdate();
                    if (matched != count)
                        throw new SQLException("UPDATE of " + table + " matched " + matched + " of " + count + " rows", "02000");
                }
            }
        }

        void delete(Connection connection, List<Change> batch) throws SQLException {
            for (int from = 0; from < batch.size(); from += MAX_ROWS_PER_DELETE) {
                int count = Math.min(MAX_ROWS_PER_DELETE, batch.size() - from);
                String sql = deleteSqlByRows.computeIfAbsent(count, rows -> "DELETE FROM " + MysqlColumn.quote(table) + " WHERE "
                        + idColumn.quotedName() + " IN (" + String.join(", ", Collections.nCopies(rows, "?")) + ")");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int row = 0; row < count; row++) statement.setObject(row + 1, batch.get(from + row).id());
                    statement.executeUpdate();
                }
            }
        }

        private String updateSql(int rows) {
            String cases = String.join(" ", Collections.nCopies(rows, "WHEN ? THEN ?"));
            return "UPDATE " + MysqlColumn.quote(table) + " SET "
                    + updatedColumns.stream().map(column -> column.quotedName() + " = CASE " + idColumn.quotedName() + " " + cases + " END")
                            .collect(Collectors.joining(", "))
                    + " WHERE " + idColumn.quotedName() + " IN (" + String.join(", ", Collections.nCopies(rows, "?")) + ")";
        }

        private MysqlColumn columnOf(EntityField field) {
            for (MysqlColumn column : columns) if (column.field == field) return column;
            throw new IllegalStateException("no column for " + field.field());
        }
    }
}
