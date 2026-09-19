package cn.managame.rdb;

import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.IndexMetadata;
import cn.managame.core.metadata.PropertyMetadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.stream.Collectors;

/** Database-specific SQL and JDBC metadata rules for relational databases. */
public interface RdbDialect {
    String name();

    String quote(String identifier);

    String sqlType(PropertyMetadata property);

    default String qualifiedTable(EntityMetadata<?> metadata) {
        return qualifiedTable(metadata, null);
    }

    default String qualifiedTable(EntityMetadata<?> metadata, String physicalName) {
        String raw = physicalName == null || physicalName.isBlank() ? metadata.rdbTable() : physicalName;
        String table = quote(raw);
        if (metadata.rdbSchema().isBlank()) return table;
        return quote(metadata.rdbSchema()) + "." + table;
    }

    default String createTableSql(EntityMetadata<?> metadata) {
        return createTableSql(metadata, null);
    }

    default String createTableSql(EntityMetadata<?> metadata, String physicalName) {
        String columns = metadata.properties().stream()
                .map(p -> quote(p.rdbName()) + " " + sqlType(p) + (p.id() ? " NOT NULL" : ""))
                .collect(Collectors.joining(", "));
        String pk = "PRIMARY KEY (" + quote(metadata.idProperty().rdbName()) + ")";
        return "CREATE TABLE IF NOT EXISTS " + qualifiedTable(metadata, physicalName)
                + " (" + columns + ", " + pk + ")";
    }

    default String addColumnSql(EntityMetadata<?> metadata, PropertyMetadata property) {
        return addColumnSql(metadata, property, null);
    }

    default String addColumnSql(EntityMetadata<?> metadata, PropertyMetadata property, String physicalName) {
        return "ALTER TABLE " + qualifiedTable(metadata, physicalName)
                + " ADD COLUMN " + quote(property.rdbName()) + " " + sqlType(property);
    }

    default String createIndexSql(EntityMetadata<?> metadata, IndexMetadata index) {
        return createIndexSql(metadata, index, null);
    }

    default String createIndexSql(EntityMetadata<?> metadata, IndexMetadata index, String physicalName) {
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + quote(index.name())
                + " ON " + qualifiedTable(metadata, physicalName)
                + " (" + index.columns().stream()
                .map(column -> quote(column.storeName()) + " " + column.direction().name())
                .collect(Collectors.joining(", ")) + ")";
    }

    default String applyLimit(String sql, int limit) {
        return limit > 0 ? sql + " LIMIT " + limit : sql;
    }

    /** Binds an already converted value using the field's backend representation. */
    default void bind(PreparedStatement statement, int index, Object value, PropertyMetadata property) throws SQLException {
        statement.setObject(index, value);
    }

    /** Configure a full-table scan. Dialects/drivers may override connection/cursor behavior. */
    default void configureScan(Connection connection, PreparedStatement statement, int fetchSize) throws SQLException {
        statement.setFetchSize(fetchSize);
    }

    /** Catalog used with DatabaseMetaData#getColumns/getIndexInfo. */
    String metadataCatalog(Connection connection, EntityMetadata<?> metadata) throws SQLException;

    /** Schema used with DatabaseMetaData#getColumns/getIndexInfo. */
    String metadataSchema(Connection connection, EntityMetadata<?> metadata) throws SQLException;
}
