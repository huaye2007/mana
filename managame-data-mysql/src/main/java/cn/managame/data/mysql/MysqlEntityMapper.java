package cn.managame.data.mysql;

import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import cn.managame.data.mysql.annotation.Column;
import cn.managame.data.mysql.annotation.Table;
import cn.managame.data.spi.Change;
import cn.managame.data.spi.EntityField;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
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
 * check that every column exists. Add {@code rewriteBatchedStatements=true}, {@code cachePrepStmts=true} and
 * {@code prepStmtCacheSqlLimit=1000000} to the JDBC URL (the README has the measurements), and keep the pool larger
 * than GameData's write threads plus the loads that run at once.
 *
 * <p>Each batch is one transaction, so a batch that failed left nothing behind and can be written again or split into
 * single rows. INSERTs and UPDATEs are plain JDBC batches, which the driver folds into one multi-row statement: an
 * UPDATE is {@code INSERT ... ON DUPLICATE KEY UPDATE} of the columns that are not keys, so a row missing from the
 * table is written again rather than failing. On a table with another unique key, a row taking a unique value another
 * row still holds updates that other row instead, as MySQL documents for this statement.
 */
public final class MysqlEntityMapper implements EntityMapper {
    /** Ids one SELECT or DELETE names at most. */
    private static final int MAX_IDS_PER_STATEMENT = 1000;
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

    /** One SELECT per thousand ids, on one connection. */
    @Override public List<Object> loadAll(EntityType type, Collection<?> ids) throws SQLException {
        TableMapping mapping = mappingOf(type);
        List<?> idList = List.copyOf(ids);
        List<Object> entities = new ArrayList<>(idList.size());
        try (Connection connection = dataSource.getConnection()) {
            for (int from = 0; from < idList.size(); from += MAX_IDS_PER_STATEMENT) {
                int count = Math.min(MAX_IDS_PER_STATEMENT, idList.size() - from);
                String sql = mapping.selectByIdsSqlByCount.computeIfAbsent(count, rows -> mapping.selectFrom + " WHERE " + mapping.idIn(rows));
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < count; i++) statement.setObject(i + 1, idList.get(from + i));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) entities.add(mapping.readEntity(resultSet));
                    }
                }
            }
        }
        return entities;
    }

    /** One SELECT of the whole table. */
    @Override public List<Object> loadTable(EntityType type) throws SQLException {
        TableMapping mapping = mappingOf(type);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(mapping.selectFrom)) {
            List<Object> entities = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) entities.add(mapping.readEntity(resultSet));
            }
            return entities;
        }
    }

    /** Encodes every row before the transaction starts, so a value that cannot be encoded never touches the table. */
    @Override public void write(EntityType type, DataOperation operation, List<? extends Change> batch) throws SQLException {
        TableMapping mapping = mappingOf(type);
        switch (operation) {
            case INSERT -> {
                List<Object[]> rows = mapping.encodeRows(mapping.columns, batch);
                Jdbc.inTransaction(dataSource, connection -> Jdbc.executeBatch(connection, mapping.insert, rows));
            }
            case UPDATE -> {
                if (mapping.upsert == null) return;   // nothing but keys, which never change
                List<Object[]> rows = mapping.encodeRows(mapping.columns, batch);
                Jdbc.inTransaction(dataSource, connection -> Jdbc.executeBatch(connection, mapping.upsert, rows));
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
        final String table, selectFrom, selectById, selectByGroup, insert;
        /** The INSERT, updating the columns that are not keys when the row exists; null when every column is a key. */
        final String upsert;
        final List<MysqlColumn> columns;
        final MysqlColumn idColumn;
        final ConcurrentHashMap<Integer, String> deleteSqlByRows = new ConcurrentHashMap<>(), selectByIdsSqlByCount = new ConcurrentHashMap<>();

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
            List<MysqlColumn> updated = columns.stream().filter(column -> !keys.contains(column.field)).toList();
            selectFrom = "SELECT " + Jdbc.columnList(columns) + " FROM " + MysqlColumn.quote(table);
            selectById = selectFrom + " WHERE " + idColumn.quotedName() + " = ?";
            selectByGroup = selectFrom + " WHERE "
                    + type.groupKeys().stream().map(field -> columnOf(field).quotedName() + " = ?").collect(Collectors.joining(" AND "));
            insert = Jdbc.insertSql(table, columns);
            upsert = updated.isEmpty() ? null : insert + " ON DUPLICATE KEY UPDATE "
                    + updated.stream().map(column -> column.quotedName() + " = VALUES(" + column.quotedName() + ")").collect(Collectors.joining(", "));
        }

        Object readEntity(ResultSet resultSet) throws SQLException {
            Object entity = type.newInstance();
            for (int i = 0; i < columns.size(); i++) columns.get(i).read(entity, resultSet, i + 1);
            return entity;
        }

        List<Object[]> encodeRows(List<MysqlColumn> encoded, List<? extends Change> batch) {
            List<Object[]> rows = new ArrayList<>(batch.size());
            for (Change change : batch) rows.add(Jdbc.encodeRow(encoded, change.entity()));
            return rows;
        }

        void delete(Connection connection, List<? extends Change> batch) throws SQLException {
            for (int from = 0; from < batch.size(); from += MAX_IDS_PER_STATEMENT) {
                int count = Math.min(MAX_IDS_PER_STATEMENT, batch.size() - from);
                String sql = deleteSqlByRows.computeIfAbsent(count, rows -> "DELETE FROM " + MysqlColumn.quote(table) + " WHERE " + idIn(rows));
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int row = 0; row < count; row++) statement.setObject(row + 1, batch.get(from + row).id());
                    statement.executeUpdate();
                }
            }
        }

        /** {@code id IN (?, ...)} with this many placeholders. */
        String idIn(int ids) { return idColumn.quotedName() + " IN (" + String.join(", ", Collections.nCopies(ids, "?")) + ")"; }

        private MysqlColumn columnOf(EntityField field) {
            for (MysqlColumn column : columns) if (column.field == field) return column;
            throw new IllegalStateException("no column for " + field.field());
        }
    }
}
