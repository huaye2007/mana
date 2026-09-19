package cn.managame.rdb;

import cn.managame.core.DataException;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.IndexMetadata;
import cn.managame.core.metadata.PropertyMetadata;

import javax.sql.DataSource;
import java.sql.*;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Safe RDB schema alignment. It only creates missing tables, columns and indexes.
 * It never drops or changes existing columns/indexes destructively.
 */
public final class RdbSchemaManager {
    private final DataSource dataSource;
    private final RdbDialect dialect;
    private final RdbSchemaReporter reporter;

    public RdbSchemaManager(DataSource dataSource, RdbDialect dialect) {
        this(dataSource, dialect, RdbSchemaReporter.stderr());
    }

    public RdbSchemaManager(DataSource dataSource, RdbDialect dialect, RdbSchemaReporter reporter) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.dialect = Objects.requireNonNull(dialect);
        this.reporter = Objects.requireNonNull(reporter);
    }

    public void ensure(EntityMetadata<?> metadata) {
        ensure(metadata, null);
    }

    public void ensure(EntityMetadata<?> metadata, String physicalName) {
        if (!metadata.compoundIndexes().isEmpty()) {
            throw new DataException("@CompoundIndex JSON declarations are MongoDB-only; use @Table(indexes = @Index(columnList = ...)) for JDBC");
        }
        String tableName = physicalName == null || physicalName.isBlank() ? metadata.rdbTable() : physicalName;
        try (Connection c = dataSource.getConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute(dialect.createTableSql(metadata, physicalName));
            }
            addMissingColumns(c, metadata, physicalName, tableName);
            addMissingIndexes(c, metadata, physicalName, tableName);
            for (RdbSchemaIssue issue : inspect(c, metadata, tableName)) reporter.report(issue);
        } catch (SQLException e) {
            throw new DataException("RDB schema initialization failed for " + metadata.type().getName()
                    + " table=" + tableName + " via " + dialect.name(), e);
        }
    }

    private void addMissingColumns(Connection c, EntityMetadata<?> metadata,
                                   String physicalName, String tableName) throws SQLException {
        Set<String> existing = existingColumns(c, metadata, tableName);
        for (PropertyMetadata p : metadata.properties()) {
            String key = p.rdbName().toLowerCase(Locale.ROOT);
            if (existing.contains(key)) continue;
            try (Statement st = c.createStatement()) {
                st.execute(dialect.addColumnSql(metadata, p, physicalName));
                existing.add(key);
            } catch (SQLException e) {
                // Multiple game servers may initialize the same schema concurrently.
                if (!existingColumns(c, metadata, tableName).contains(key)) throw e;
                existing.add(key);
            }
        }
    }

    private Set<String> existingColumns(Connection c, EntityMetadata<?> metadata, String tableName) throws SQLException {
        Set<String> existing = new HashSet<>();
        DatabaseMetaData dm = c.getMetaData();
        try (ResultSet rs = dm.getColumns(
                dialect.metadataCatalog(c, metadata),
                dialect.metadataSchema(c, metadata),
                tableName, null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        return existing;
    }

    private void addMissingIndexes(Connection c, EntityMetadata<?> metadata,
                                   String physicalName, String tableName) throws SQLException {
        Set<String> existing = existingIndexes(c, metadata, tableName);
        for (IndexMetadata idx : metadata.indexes()) {
            String key = idx.name().toLowerCase(Locale.ROOT);
            if (existing.contains(key)) continue;
            try (Statement st = c.createStatement()) {
                st.execute(dialect.createIndexSql(metadata, idx, physicalName));
                existing.add(key);
            } catch (SQLException e) {
                if (!existingIndexes(c, metadata, tableName).contains(key)) throw e;
                existing.add(key);
            }
        }
    }

    private Set<String> existingIndexes(Connection c, EntityMetadata<?> metadata, String tableName) throws SQLException {
        Set<String> existing = new HashSet<>();
        DatabaseMetaData dm = c.getMetaData();
        try (ResultSet rs = dm.getIndexInfo(
                dialect.metadataCatalog(c, metadata),
                dialect.metadataSchema(c, metadata),
                tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null) existing.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return existing;
    }

    /** Returns non-destructive differences for an already existing table. */
    public List<RdbSchemaIssue> inspect(EntityMetadata<?> metadata) {
        return inspect(metadata, null);
    }

    public List<RdbSchemaIssue> inspect(EntityMetadata<?> metadata, String physicalName) {
        String tableName = physicalName == null || physicalName.isBlank() ? metadata.rdbTable() : physicalName;
        try (Connection c = dataSource.getConnection()) {
            return inspect(c, metadata, tableName);
        } catch (SQLException e) {
            throw new DataException("RDB schema inspection failed for " + metadata.type().getName()
                    + " table=" + tableName + " via " + dialect.name(), e);
        }
    }

    private List<RdbSchemaIssue> inspect(Connection c, EntityMetadata<?> metadata, String tableName) throws SQLException {
        List<RdbSchemaIssue> issues = new ArrayList<>();
        Map<String, ColumnInfo> columns = columnInfo(c, metadata, tableName);
        for (PropertyMetadata property : metadata.properties()) {
            ColumnInfo actual = columns.get(property.rdbName().toLowerCase(Locale.ROOT));
            if (actual == null) continue; // addMissingColumns owns missing-column handling.
            if (property.id() && actual.nullable == DatabaseMetaData.columnNullable) {
                issues.add(new RdbSchemaIssue(RdbSchemaIssue.Kind.COLUMN_NULLABILITY, tableName,
                        property.rdbName(), "NOT NULL", "NULLABLE"));
            }
            String expected = dialect.sqlType(property);
            String typeIssue = typeIssue(property, expected, actual);
            if (typeIssue != null) {
                issues.add(new RdbSchemaIssue(RdbSchemaIssue.Kind.COLUMN_TYPE, tableName,
                        property.rdbName(), expected, actual.typeName));
                continue;
            }
            java.util.regex.Matcher decimal = java.util.regex.Pattern.compile("(?:DECIMAL|NUMERIC)\\((\\d+),\\s*(\\d+)\\)")
                    .matcher(normalize(expected));
            if (decimal.matches() && actual.size > 0 && actual.scale != null) {
                int precision = Integer.parseInt(decimal.group(1));
                int scale = Integer.parseInt(decimal.group(2));
                if (actual.scale < scale || actual.size - actual.scale < precision - scale) {
                    issues.add(new RdbSchemaIssue(RdbSchemaIssue.Kind.COLUMN_PRECISION, tableName,
                            property.rdbName(), expected, actual.typeName + "(" + actual.size + "," + actual.scale + ")"));
                }
            }
            if (requiresLengthCheck(property) && actual.size > 0 && actual.size < property.length()) {
                issues.add(new RdbSchemaIssue(RdbSchemaIssue.Kind.COLUMN_LENGTH, tableName,
                        property.rdbName(), ">=" + property.length(), String.valueOf(actual.size)));
            }
        }

        List<String> primaryKey = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(
                dialect.metadataCatalog(c, metadata), dialect.metadataSchema(c, metadata), tableName)) {
            while (rs.next()) primaryKey.add(rs.getString("COLUMN_NAME"));
        }
        String expectedId = metadata.idProperty().rdbName();
        if (primaryKey.size() != 1 || !expectedId.equalsIgnoreCase(primaryKey.getFirst())) {
            issues.add(new RdbSchemaIssue(RdbSchemaIssue.Kind.PRIMARY_KEY, tableName,
                    expectedId, "PRIMARY KEY (" + expectedId + ")", primaryKey.toString()));
        }

        Map<String, IndexInfo> indexes = indexInfo(c, metadata, tableName);
        for (IndexMetadata index : metadata.indexes()) {
            IndexInfo actual = indexes.get(index.name().toLowerCase(Locale.ROOT));
            if (actual == null) continue;
            boolean mismatch = actual.unique != index.unique() || actual.columns.size() != index.columns().size();
            List<IndexColumn> actualColumns = new ArrayList<>(actual.columns.values());
            for (int i = 0; !mismatch && i < actualColumns.size(); i++) {
                IndexColumn column = actualColumns.get(i);
                IndexMetadata.Column expected = index.columns().get(i);
                mismatch = !expected.storeName().equalsIgnoreCase(column.name)
                        || (column.direction != null && !column.direction.equalsIgnoreCase(expected.direction().name()));
            }
            if (mismatch) {
                issues.add(new RdbSchemaIssue(RdbSchemaIssue.Kind.INDEX_DEFINITION, tableName,
                        index.name(), "unique=" + index.unique() + ", columns=" + index.columns(),
                        "unique=" + actual.unique + ", columns=" + actualColumns));
            }
        }
        return List.copyOf(issues);
    }

    private Map<String, ColumnInfo> columnInfo(Connection c, EntityMetadata<?> metadata, String tableName) throws SQLException {
        Map<String, ColumnInfo> result = new HashMap<>();
        DatabaseMetaData dm = c.getMetaData();
        try (ResultSet rs = dm.getColumns(
                dialect.metadataCatalog(c, metadata), dialect.metadataSchema(c, metadata), tableName, null)) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                Number scale = (Number) rs.getObject("DECIMAL_DIGITS");
                Number nullable = (Number) rs.getObject("NULLABLE");
                result.put(name.toLowerCase(Locale.ROOT), new ColumnInfo(type == null ? "" : type, size,
                        scale == null ? null : scale.intValue(),
                        nullable == null ? DatabaseMetaData.columnNullableUnknown : nullable.intValue()));
            }
        }
        return result;
    }

    private Map<String, IndexInfo> indexInfo(Connection c, EntityMetadata<?> metadata, String tableName) throws SQLException {
        Map<String, IndexInfo> result = new HashMap<>();
        try (ResultSet rs = c.getMetaData().getIndexInfo(
                dialect.metadataCatalog(c, metadata), dialect.metadataSchema(c, metadata), tableName, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null) continue;
                String column = rs.getString("COLUMN_NAME");
                if (column == null) continue;
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                IndexInfo info = result.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new IndexInfo(unique));
                String direction = rs.getString("ASC_OR_DESC");
                info.columns.put(rs.getInt("ORDINAL_POSITION"), new IndexColumn(column,
                        "A".equalsIgnoreCase(direction) ? "ASC" : "D".equalsIgnoreCase(direction) ? "DESC" : null));
            }
        }
        return result;
    }

    private static final class IndexInfo {
        final boolean unique;
        final java.util.SortedMap<Integer, IndexColumn> columns = new java.util.TreeMap<>();
        IndexInfo(boolean unique) { this.unique = unique; }
    }
    private record IndexColumn(String name, String direction) { }

    private static boolean requiresLengthCheck(PropertyMetadata property) {
        return property.columnType() == cn.managame.annotation.ColumnType.STRING
                || (property.columnType() == cn.managame.annotation.ColumnType.AUTO
                && property.type() == String.class);
    }

    private static String typeIssue(PropertyMetadata property, String expected, ColumnInfo actual) {
        String e = normalize(expected);
        String a = normalize(actual.typeName);
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.JSON) {
            return (a.contains("JSON") || a.contains("TEXT")) ? null : "json";
        }
        if (property.storageKind() == cn.managame.core.metadata.StorageKind.BINARY) {
            return (a.contains("BLOB") || a.contains("BINARY") || a.contains("BYTEA")) ? null : "binary";
        }
        if (property.columnType() == cn.managame.annotation.ColumnType.TEXT) {
            return (a.contains("TEXT") || a.contains("CLOB")) ? null : "text";
        }
        if (requiresLengthCheck(property)) {
            return (a.contains("CHAR") || a.contains("TEXT")) ? null : "string";
        }
        // Keep scalar validation conservative across JDBC drivers and aliases.
        if (e.startsWith("BIGINT")) return (a.contains("BIGINT") || a.equals("INT8")) ? null : "bigint";
        if (e.startsWith("SMALLINT")) return (a.contains("SMALLINT") || a.equals("INT2")) ? null : "smallint";
        if (e.startsWith("INT") || e.startsWith("INTEGER")) return (a.contains("INT") || a.contains("INTEGER")) ? null : "integer";
        if (e.startsWith("BOOLEAN") || e.startsWith("TINYINT(1)")) return (a.contains("BOOL") || a.contains("BIT") || a.contains("TINYINT")) ? null : "boolean";
        if (e.startsWith("FLOAT") || e.startsWith("REAL")) return (a.contains("FLOAT") || a.contains("REAL") || a.equals("FLOAT4")) ? null : "float";
        if (e.startsWith("DOUBLE")) return (a.contains("DOUBLE") || a.contains("FLOAT8")) ? null : "double";
        if (e.startsWith("DECIMAL") || e.startsWith("NUMERIC")) return (a.contains("DECIMAL") || a.contains("NUMERIC")) ? null : "decimal";
        if (e.startsWith("TIMESTAMP") || e.startsWith("DATETIME")) return (a.contains("TIMESTAMP") || a.contains("DATETIME")) ? null : "timestamp";
        if (e.equals("DATE")) return a.equals("DATE") ? null : "date";
        if (e.startsWith("TIME")) return (a.equals("TIME") || a.startsWith("TIME ") || a.equals("TIMETZ")) ? null : "time";
        if (e.startsWith("UUID")) return (a.contains("UUID") || a.contains("CHAR")) ? null : "uuid";
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record ColumnInfo(String typeName, int size, Integer scale, int nullable) { }
}
