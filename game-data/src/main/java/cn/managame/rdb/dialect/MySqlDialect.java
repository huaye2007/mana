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

public class MySqlDialect extends AbstractRdbDialect {
    @Override public String name() { return "mysql"; }

    @Override
    public String quote(String identifier) {
        validateIdentifier(identifier);
        return "`" + identifier + "`";
    }

    @Override
    public String sqlType(PropertyMetadata property) {
        if (property.columnType() == ColumnType.TEXT) return "LONGTEXT";
        if (property.columnType() == ColumnType.STRING) return "VARCHAR(" + property.length() + ")";
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.JSON) return "JSON";
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.BINARY) return "LONGBLOB";
        Class<?> type = property.type();
        if (type == long.class || type == Long.class) return "BIGINT";
        if (type == int.class || type == Integer.class) return "INT";
        if (type == short.class || type == Short.class) return "SMALLINT";
        if (type == byte.class || type == Byte.class) return "TINYINT";
        if (type == boolean.class || type == Boolean.class) return "TINYINT(1)";
        if (type == float.class || type == Float.class) return "FLOAT";
        if (type == double.class || type == Double.class) return "DOUBLE";
        if (type == BigDecimal.class) return "DECIMAL(38,10)";
        if (type == BigInteger.class) return "DECIMAL(65,0)";
        if (type == char.class || type == Character.class) return "CHAR(1)";
        if (type == String.class) return "VARCHAR(" + property.length() + ")";
        if (type == byte[].class) return "LONGBLOB";
        if (type == Instant.class || type == LocalDateTime.class) return "DATETIME(6)";
        if (type == LocalDate.class) return "DATE";
        if (type == LocalTime.class) return "TIME(6)";
        if (type == UUID.class) return "CHAR(36)";
        if (type.isEnum()) return "VARCHAR(64)";
        throw new DataException("Unsupported MySQL field type: " + type.getName());
    }

    @Override
    public String createTableSql(EntityMetadata<?> metadata) {
        return createTableSql(metadata, null);
    }

    @Override
    public String createTableSql(EntityMetadata<?> metadata, String physicalName) {
        return super.createTableSql(metadata, physicalName) + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public void configureScan(Connection connection, PreparedStatement statement, int fetchSize) throws SQLException {
        // Connector/J streams rows when fetch size is Integer.MIN_VALUE without requiring useCursorFetch in the URL.
        statement.setFetchSize(Integer.MIN_VALUE);
    }

    @Override
    public String metadataCatalog(Connection connection, EntityMetadata<?> metadata) throws SQLException {
        return metadata.rdbSchema().isBlank() ? connection.getCatalog() : metadata.rdbSchema();
    }

    @Override
    public String metadataSchema(Connection connection, EntityMetadata<?> metadata) {
        return null;
    }
}
