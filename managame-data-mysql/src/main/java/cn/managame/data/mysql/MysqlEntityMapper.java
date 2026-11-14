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
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Keeps entities in one MySQL database, each entity type in its {@link Table}, a column per saved field named after
 * the field in snake case or by {@link Column}. While GameData is built, {@link #prepare} creates what a table lacks:
 * the table itself, the columns of fields added since, and a grouped type's unique key on its group and map key
 * columns; it never drops or changes anything, and a mapper built with {@code createMissing(false)} creates nothing
 * and leaves the tables to the application. A field is kept by the rules, a scalar as a column of its own type and
 * anything else as JSON, unless the application's {@code columnCodecs} gives it a {@link ColumnCodec}. Add
 * {@code rewriteBatchedStatements=true}, {@code cachePrepStmts=true} and {@code prepStmtCacheSqlLimit=1000000} to the
 * JDBC URL (the README has the measurements), and keep the pool larger than GameData's write threads plus the loads
 * that run at once.
 *
 * <p>Each table's statements are built once, when it is prepared: an INSERT, an upsert, a DELETE by id, a SELECT by
 * id, by group, of the whole table and of a hundred ids. Batches of INSERTs, UPDATEs and DELETEs are JDBC batches of
 * the single statements, and a batch SELECT fills its last hundred by repeating an id, so no other SQL is ever sent and
 * a connection's statement cache holds seven statements a table.
 *
 * <p>Each batch is one transaction, so a batch that failed left nothing behind and can be written again or split into
 * single rows. INSERTs and UPDATEs are plain JDBC batches, which the driver folds into one multi-row statement: an
 * UPDATE is {@code INSERT ... ON DUPLICATE KEY UPDATE} of the columns that are not keys, so a row missing from the
 * table is written again rather than failing. On a table with another unique key, a row taking a unique value another
 * row still holds updates that other row instead, as MySQL documents for this statement.
 */
public final class MysqlEntityMapper implements EntityMapper {
    /** Ids the batch SELECT names: always this many, the last of a load repeated to fill them. */
    private static final int IDS_PER_SELECT = 100;
    private final DataSource dataSource;
    private final JsonCodec jsonCodec;
    private final Function<EntityField, ColumnCodec> columnCodecs;
    private final boolean createMissing;
    private final ConcurrentHashMap<EntityType, TableMapping> tables = new ConcurrentHashMap<>();
    /** Whether the JDBC URL has been looked at; read and set while GameData is built, on its one thread. */
    private boolean urlChecked;

    /** For entities whose fields are all scalars; creates what its tables lack. */
    public MysqlEntityMapper(DataSource dataSource) { this(dataSource, null); }

    /**
     * Creates what its tables lack; {@link #builder} has the other settings.
     *
     * @param jsonCodec encodes the fields that are not scalars, or null when no entity has one
     */
    public MysqlEntityMapper(DataSource dataSource, JsonCodec jsonCodec) { this(dataSource, jsonCodec, field -> null, true); }

    private MysqlEntityMapper(DataSource dataSource, JsonCodec jsonCodec, Function<EntityField, ColumnCodec> columnCodecs, boolean createMissing) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jsonCodec = jsonCodec;
        this.columnCodecs = columnCodecs;
        this.createMissing = createMissing;
    }

    /** A mapper with settings the constructors leave at their defaults: codecs of its own for fields, tables it does not touch. */
    public static Builder builder(DataSource dataSource) { return new Builder(Objects.requireNonNull(dataSource, "dataSource")); }

    public static final class Builder {
        private final DataSource dataSource;
        private JsonCodec jsonCodec;
        private Function<EntityField, ColumnCodec> columnCodecs = field -> null;
        private boolean createMissing = true;

        private Builder(DataSource dataSource) { this.dataSource = dataSource; }

        /** Encodes the fields that are neither scalars nor have a codec of their own; none by default, for entities without such a field. */
        public Builder json(JsonCodec codec) {
            jsonCodec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * How fields are kept otherwise than by the rules: asked once for each field of each entity type while GameData
         * is built, with the field's name, type and Java field, so it can go by any of them or by an annotation of the
         * application's own; it returns the field's {@link ColumnCodec}, or null for a field kept by the rules. Not
         * asked for the id or a group or map key, which are always kept as they are.
         */
        public Builder columnCodecs(Function<EntityField, ColumnCodec> codecs) {
            columnCodecs = Objects.requireNonNull(codecs, "codecs");
            return this;
        }

        /**
         * Whether the mapper creates what its tables lack while GameData is built: a table, the columns of fields added
         * since, a grouped type's unique key on its group and map key columns. True by default. It never drops or
         * changes anything. With false the tables are the application's alone: a missing table or column fails the
         * build, and a grouped type's table without an index on its group columns is warned of.
         */
        public Builder createMissing(boolean create) {
            createMissing = create;
            return this;
        }

        public MysqlEntityMapper build() { return new MysqlEntityMapper(dataSource, jsonCodec, columnCodecs, createMissing); }
    }

    /**
     * Builds the statements of {@code type}'s table, creates what the table lacks unless told not to, and reads it
     * once. IllegalArgumentException when the class has no {@code @Table} or two columns of one name,
     * IllegalStateException for a JSON field without a JsonCodec, and DataException ({@code DATA_LOAD_FAILED}) when the
     * table or a column cannot be created or read.
     */
    @Override public void prepare(EntityType type) {
        TableMapping mapping = new TableMapping(type);
        if (!urlChecked) {
            urlChecked = true;
            Jdbc.warnUnlessBatchesAreRewritten(dataSource);
        }
        List<MysqlColumn> groupColumns = type.groupKeys().stream().map(mapping::columnOf).toList();
        if (createMissing)
            Jdbc.createMissing(dataSource, mapping.table, mapping.columns, mapping.idColumn, groupColumns, type.mapKeys().stream().map(mapping::columnOf).toList());
        Jdbc.checkColumnsExist(dataSource, mapping.table, mapping.columns);
        if (!createMissing) Jdbc.warnWithoutGroupIndex(dataSource, mapping.table, groupColumns);
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

    /** The batch SELECT once per hundred ids, on one connection; a repeated id finds its row once. */
    @Override public List<Object> loadAll(EntityType type, Collection<?> ids) throws SQLException {
        TableMapping mapping = mappingOf(type);
        List<?> idList = List.copyOf(ids);
        List<Object> entities = new ArrayList<>(idList.size());
        if (idList.isEmpty()) return entities;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(mapping.selectByIds)) {
            for (int from = 0; from < idList.size(); from += IDS_PER_SELECT) {
                for (int i = 0; i < IDS_PER_SELECT; i++) statement.setObject(i + 1, idList.get(Math.min(from + i, idList.size() - 1)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) entities.add(mapping.readEntity(resultSet));
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

    /**
     * What every mapper tries again, a lost or refused connection, a timeout, a deadlock or lock wait, and what MySQL
     * cannot do now, which says nothing about the rows: read-only, as a primary is once another took over, the disk or a
     * table full, out of memory or resources, the user's connections used up, a statement killed. Those wait for the
     * next round, where a failure of the data splits the batch and gives up the rows that still fail.
     */
    @Override public boolean isRetryable(Throwable failure) { return Jdbc.isRetryable(failure); }

    private TableMapping mappingOf(EntityType type) {
        TableMapping mapping = tables.get(type);
        if (mapping == null) throw new IllegalStateException(type + " was not prepared by this mapper");
        return mapping;
    }

    /** One entity type's table: its columns and statements. */
    private final class TableMapping {
        final EntityType type;
        final String table, selectFrom, selectById, selectByIds, selectByGroup, insert, delete;
        /** The INSERT, updating the columns that are not keys when the row exists; null when every column is a key. */
        final String upsert;
        final List<MysqlColumn> columns;
        final MysqlColumn idColumn;

        TableMapping(EntityType type) {
            this.type = type;
            Table annotation = type.entityClass().getAnnotation(Table.class);
            if (annotation == null) throw new IllegalArgumentException(type + " needs @Table to be kept in MySQL");
            table = MysqlColumn.checkName(annotation.name());
            columns = Jdbc.columns(type, jsonCodec, columnCodecs);
            idColumn = columnOf(type.id());
            Set<EntityField> keys = new HashSet<>(type.groupKeys());
            keys.addAll(type.mapKeys());
            keys.add(type.id());
            List<MysqlColumn> updated = columns.stream().filter(column -> !keys.contains(column.field)).toList();
            selectFrom = "SELECT " + Jdbc.columnList(columns) + " FROM " + MysqlColumn.quote(table);
            selectById = selectFrom + " WHERE " + idColumn.quotedName() + " = ?";
            selectByIds = selectFrom + " WHERE " + idColumn.quotedName() + " IN (" + String.join(", ", Collections.nCopies(IDS_PER_SELECT, "?")) + ")";
            delete = "DELETE FROM " + MysqlColumn.quote(table) + " WHERE " + idColumn.quotedName() + " = ?";
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

        /** The DELETE by id as one JDBC batch. */
        void delete(Connection connection, List<? extends Change> batch) throws SQLException {
            List<Object[]> ids = new ArrayList<>(batch.size());
            for (Change change : batch) ids.add(new Object[] {change.id()});
            Jdbc.executeBatch(connection, delete, ids);
        }

        private MysqlColumn columnOf(EntityField field) {
            for (MysqlColumn column : columns) if (column.field == field) return column;
            throw new IllegalStateException("no column for " + field.field());
        }
    }
}
