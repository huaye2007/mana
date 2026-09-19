package cn.managame.rdb.dialect;

import cn.managame.core.DataException;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.PropertyMetadata;

import cn.managame.annotation.ColumnType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.*;
import java.util.UUID;

public final class PostgreSqlDialect extends AbstractRdbDialect {
    @Override public String name() { return "postgresql"; }

    @Override
    public String quote(String identifier) {
        validateIdentifier(identifier);
        return "\"" + identifier + "\"";
    }

    @Override
    public String sqlType(PropertyMetadata property) {
        if (property.columnType() == ColumnType.TEXT) return "TEXT";
        if (property.columnType() == ColumnType.STRING) return "VARCHAR(" + property.length() + ")";
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.JSON) return "JSONB";
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.BINARY) return "BYTEA";
        Class<?> type = property.type();
        if (type == long.class || type == Long.class) return "BIGINT";
        if (type == int.class || type == Integer.class) return "INTEGER";
        if (type == short.class || type == Short.class) return "SMALLINT";
        if (type == byte.class || type == Byte.class) return "SMALLINT";
        if (type == boolean.class || type == Boolean.class) return "BOOLEAN";
        if (type == float.class || type == Float.class) return "REAL";
        if (type == double.class || type == Double.class) return "DOUBLE PRECISION";
        if (type == BigDecimal.class) return "NUMERIC(38,10)";
        if (type == BigInteger.class) return "NUMERIC(65,0)";
        if (type == char.class || type == Character.class) return "CHAR(1)";
        if (type == String.class) return "VARCHAR(" + property.length() + ")";
        if (type == byte[].class) return "BYTEA";
        if (type == Instant.class) return "TIMESTAMP(6) WITH TIME ZONE";
        if (type == LocalDateTime.class) return "TIMESTAMP(6) WITHOUT TIME ZONE";
        if (type == LocalDate.class) return "DATE";
        if (type == LocalTime.class) return "TIME(6) WITHOUT TIME ZONE";
        if (type == UUID.class) return "UUID";
        if (type.isEnum()) return "VARCHAR(64)";
        throw new DataException("Unsupported PostgreSQL field type: " + type.getName());
    }

    @Override
    public void bind(PreparedStatement statement, int index, Object value, PropertyMetadata property) throws SQLException {
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.JSON
                || (property.type() == UUID.class && property.columnType() == ColumnType.AUTO)) {
            statement.setObject(index, value, java.sql.Types.OTHER);
        } else {
            super.bind(statement, index, value, property);
        }
    }

    @Override
    public void configureScan(Connection connection, PreparedStatement statement, int fetchSize) throws SQLException {
        // PostgreSQL JDBC uses cursor-based fetching only when auto-commit is disabled.
        connection.setAutoCommit(false);
        connection.setReadOnly(true);
        statement.setFetchSize(fetchSize);
    }

    @Override
    public String metadataCatalog(Connection connection, EntityMetadata<?> metadata) throws SQLException {
        return connection.getCatalog();
    }

    @Override
    public String metadataSchema(Connection connection, EntityMetadata<?> metadata) {
        return metadata.rdbSchema().isBlank() ? "public" : metadata.rdbSchema();
    }
}
