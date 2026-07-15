package cn.managame.jpa.rdb.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbFieldMetadata;
import cn.managame.jpa.rdb.metadata.RdbIndexMetadata;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL DDL 自动建表/迁移工具。
 * <p>
 * 根据 {@link RdbEntityMetadata} 自动生成 CREATE TABLE / ALTER TABLE 语句。
 * 支持三种模式：
 * <ul>
 *   <li>{@link Mode#CREATE} — 仅创建不存在的表（安全，适用于生产）</li>
 *   <li>{@link Mode#UPDATE} — 创建不存在的表 + 为已有表添加缺失的列和索引（适用于开发迭代）</li>
 *   <li>{@link Mode#GENERATE_ONLY} — 仅生成 SQL 不执行（用于审查）</li>
 * </ul>
 */
public class MysqlSchemaGenerator {

    private static final Logger log = LoggerFactory.getLogger(MysqlSchemaGenerator.class);

    public enum Mode {
        /** 仅创建不存在的表 */
        CREATE,
        /** 创建不存在的表 + 添加缺失列和索引 */
        UPDATE,
        /** 仅生成 SQL，不执行 */
        GENERATE_ONLY
    }

    private final DataSource dataSource;
    private final ObjectMapper json;

    public MysqlSchemaGenerator(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public MysqlSchemaGenerator(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json != null ? json : new ObjectMapper();
    }

    /**
     * 对注册表中所有 RDB 实体执行 schema 同步。
     *
     * @param registry 元数据注册表
     * @param mode     执行模式
     * @return 生成的 SQL 列表（无论是否执行都会返回）
     */
    public List<String> synchronize(MetadataRegistry registry, Mode mode) {
        List<String> allSql = new ArrayList<>();
        List<EntityMetadata> rdbEntities = registry.getByModel(ModelTypes.RDB);

        for (EntityMetadata meta : rdbEntities) {
            if (meta instanceof RdbEntityMetadata rdbMeta && !rdbMeta.hasShardKey()) {
                allSql.addAll(synchronizeEntity(rdbMeta, mode));
            }
        }
        return allSql;
    }

    /**
     * 对单个实体执行 schema 同步。
     */
    public List<String> synchronizeEntity(RdbEntityMetadata metadata, Mode mode) {
        List<String> sqls = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            boolean tableExists = tableExists(conn, metadata.tableName());

            if (!tableExists) {
                String createSql = generateCreateTable(metadata);
                sqls.add(createSql);
                // 索引 SQL
                for (String indexSql : generateIndexes(metadata)) {
                    sqls.add(indexSql);
                }
            } else if (mode == Mode.UPDATE || mode == Mode.GENERATE_ONLY) {
                sqls.addAll(generateExistingTableDiff(conn, metadata, mode == Mode.GENERATE_ONLY));
            }

            if (mode != Mode.GENERATE_ONLY && !sqls.isEmpty()) {
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : sqls) {
                        log.info("[SchemaGenerator] Executing: {}", sql);
                        stmt.execute(sql);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Schema synchronization failed for: {}", metadata.tableName(), e);
            throw new RuntimeException("Schema sync failed for " + metadata.tableName(), e);
        }
        return sqls;
    }

    // ==================== SQL 生成 ====================

    /**
     * 按给定物理表名创建表（CREATE TABLE IF NOT EXISTS + 索引），用于分表场景按需建物理表。
     * 并发建表安全：建表 IF NOT EXISTS 幂等；索引重名（1061 ER_DUP_KEYNAME，并发已建）忽略。
     */
    public void createTable(RdbEntityMetadata metadata, String tableName) {
        List<String> sqls = new ArrayList<>();
        sqls.add(generateCreateTable(metadata, tableName));
        sqls.addAll(generateIndexes(metadata, tableName));
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String sql : sqls) {
                try {
                    stmt.execute(sql);
                } catch (java.sql.SQLException e) {
                    if (e.getErrorCode() != 1061) { // ER_DUP_KEYNAME：并发已建同名索引
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Create physical table failed: " + tableName, e);
        }
    }

    String generateCreateTable(RdbEntityMetadata metadata) {
        return generateCreateTable(metadata, metadata.tableName());
    }

    String generateCreateTable(RdbEntityMetadata metadata, String tableName) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(MysqlDialect.quoteIdentifier(tableName)).append(" (\n");

        List<String> columnDefs = new ArrayList<>();
        for (RdbFieldMetadata field : metadata.fields()) {
            columnDefs.add(generateColumnDef(field));
        }
        columnDefs.add("PRIMARY KEY (" + MysqlDialect.quoteIdentifier(metadata.idField().columnName()) + ")");

        sb.append(String.join(",\n", columnDefs));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        return sb.toString();
    }

    private String generateColumnDef(RdbFieldMetadata field) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(MysqlDialect.quoteIdentifier(field.columnName())).append(" ");
        sb.append(MysqlTypeMapping.toSqlType(field.javaType(), field.length(), field.isJsonField(), field.sqlType()));
        if (field.isPrimaryKey()) {
            sb.append(" NOT NULL");
        }
        if (!field.defaultValue().isEmpty()) {
            sb.append(" DEFAULT ").append(toSqlDefaultValue(field));
        } else if (field.isVersionField()) {
            sb.append(" DEFAULT 0");
        }
        return sb.toString();
    }

    private String toSqlDefaultValue(RdbFieldMetadata field) {
        String value = field.defaultValue();
        if (field.isJsonField()) {
            validateJsonDefault(field, value);
            return "(" + quoteSqlString(value) + ")";
        }
        Class<?> javaType = field.javaType();
        if (isNumericType(javaType)) {
            validateNumericDefault(field, value);
            return value.trim();
        }
        if (isBooleanType(javaType)) {
            return toBooleanDefault(field, value);
        }
        return quoteSqlString(value);
    }

    private String quoteSqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private void validateJsonDefault(RdbFieldMetadata field, String value) {
        try {
            json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid json default value for "
                    + field.propertyName() + ": " + value, e);
        }
    }

    private boolean isNumericType(Class<?> javaType) {
        return javaType == byte.class || javaType == Byte.class
                || javaType == short.class || javaType == Short.class
                || javaType == int.class || javaType == Integer.class
                || javaType == long.class || javaType == Long.class
                || javaType == float.class || javaType == Float.class
                || javaType == double.class || javaType == Double.class
                || javaType == BigDecimal.class || javaType == BigInteger.class;
    }

    private boolean isBooleanType(Class<?> javaType) {
        return javaType == boolean.class || javaType == Boolean.class;
    }

    private void validateNumericDefault(RdbFieldMetadata field, String value) {
        try {
            new java.math.BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric default value for "
                    + field.propertyName() + ": " + value, e);
        }
    }

    private String toBooleanDefault(RdbFieldMetadata field, String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1" -> "1";
            case "false", "0" -> "0";
            default -> throw new IllegalArgumentException("Invalid boolean default value for "
                    + field.propertyName() + ": " + value);
        };
    }

    List<String> generateIndexes(RdbEntityMetadata metadata) {
        return generateIndexes(metadata, metadata.tableName());
    }

    List<String> generateIndexes(RdbEntityMetadata metadata, String tableName) {
        List<String> sqls = new ArrayList<>();
        for (RdbIndexMetadata index : effectiveIndexes(metadata)) {
            sqls.add(generateCreateIndex(tableName, index));
        }
        return sqls;
    }

    /**
     * 有效索引即实体声明的 {@code @Index}。唯一约束统一通过 {@code @Index(unique=true)} 声明，
     * 不再从列级注解推导。
     */
    private List<RdbIndexMetadata> effectiveIndexes(RdbEntityMetadata metadata) {
        return metadata.indexes();
    }

    private String generateCreateIndex(String tableName, RdbIndexMetadata index) {
        String columns = index.columns().stream()
                .map(MysqlDialect::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String indexType = index.unique() ? "UNIQUE INDEX" : "INDEX";
        return "CREATE " + indexType + " " + MysqlDialect.quoteIdentifier(index.name())
                + " ON " + MysqlDialect.quoteIdentifier(tableName) + " (" + columns + ")";
    }

    String generateAddColumn(String tableName, RdbFieldMetadata field) {
        return "ALTER TABLE " + MysqlDialect.quoteIdentifier(tableName)
                + " ADD COLUMN " + generateColumnDef(field).trim();
    }

    private List<String> generateExistingTableDiff(Connection conn, RdbEntityMetadata metadata,
            boolean includeManualReport) throws Exception {
        List<String> diff = new ArrayList<>();
        Map<String, ExistingColumn> existingColumns = getExistingColumnMetadata(conn, metadata.tableName());
        Set<String> expectedColumns = new HashSet<>();
        for (RdbFieldMetadata field : metadata.fields()) {
            String columnKey = field.columnName().toLowerCase(Locale.ROOT);
            expectedColumns.add(columnKey);
            ExistingColumn existing = existingColumns.get(columnKey);
            if (existing == null) {
                diff.add(generateAddColumn(metadata.tableName(), field));
            } else if (includeManualReport) {
                addColumnManualReports(diff, metadata.tableName(), field, existing);
            }
        }
        if (includeManualReport) {
            for (ExistingColumn existing : existingColumns.values()) {
                if (!expectedColumns.contains(existing.name().toLowerCase(Locale.ROOT))) {
                    diff.add(manualMigration("table " + quoted(metadata.tableName())
                            + " column " + quoted(existing.name())
                            + " exists in database but not in entity metadata; review drop/rename manually"));
                }
            }
        }

        Map<String, ExistingIndex> existingIndexes = getExistingIndexMetadata(conn, metadata.tableName());
        List<RdbIndexMetadata> expectedIndexes = effectiveIndexes(metadata);
        Set<String> expectedIndexNames = new HashSet<>();
        for (RdbIndexMetadata index : expectedIndexes) {
            String indexKey = index.name().toLowerCase(Locale.ROOT);
            expectedIndexNames.add(indexKey);
            ExistingIndex existing = existingIndexes.get(indexKey);
            if (existing == null) {
                diff.add(generateCreateIndex(metadata.tableName(), index));
            } else if (includeManualReport && !indexMatches(index, existing)) {
                diff.add(manualMigration("table " + quoted(metadata.tableName())
                        + " index " + quoted(existing.name)
                        + " differs; expected " + describeIndex(index)
                        + ", actual " + existing.describe()
                        + "; review rebuild manually"));
            }
        }
        if (includeManualReport) {
            for (ExistingIndex existing : existingIndexes.values()) {
                String indexKey = existing.name.toLowerCase(Locale.ROOT);
                if (!"primary".equals(indexKey) && !expectedIndexNames.contains(indexKey)) {
                    diff.add(manualMigration("table " + quoted(metadata.tableName())
                            + " index " + quoted(existing.name)
                            + " exists in database but not in entity metadata; review drop/rename manually"));
                }
            }
        }
        return diff;
    }

    private void addColumnManualReports(List<String> diff, String tableName, RdbFieldMetadata field,
            ExistingColumn existing) {
        String expectedType = MysqlTypeMapping.toSqlType(field.javaType(), field.length(), field.isJsonField(), field.sqlType());
        if (!columnTypeMatches(expectedType, existing)) {
            diff.add(manualMigration("table " + quoted(tableName)
                    + " column " + quoted(field.columnName())
                    + " type differs; expected " + expectedType
                    + ", actual " + existing.typeDescription()
                    + "; review ALTER COLUMN manually"));
        }

        String expectedDefault = expectedDefault(field);
        if (!defaultsEqual(expectedDefault, existing.columnDef())) {
            diff.add(manualMigration("table " + quoted(tableName)
                    + " column " + quoted(field.columnName())
                    + " default differs; expected " + describeDefault(expectedDefault)
                    + ", actual " + describeDefault(existing.columnDef())
                    + "; review ALTER DEFAULT manually"));
        }

        if (field.isPrimaryKey() && existing.nullable() == DatabaseMetaData.columnNullable) {
            diff.add(manualMigration("table " + quoted(tableName)
                    + " column " + quoted(field.columnName())
                    + " is nullable but entity primary key requires NOT NULL"));
        }
    }

    private String expectedDefault(RdbFieldMetadata field) {
        if (!field.defaultValue().isEmpty()) {
            return toSqlDefaultValue(field);
        }
        if (field.isVersionField()) {
            return "0";
        }
        return null;
    }

    private boolean defaultsEqual(String expected, String actual) {
        return normalizeDefault(expected).equals(normalizeDefault(actual));
    }

    private String normalizeDefault(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.length() >= 2 && normalized.startsWith("(") && normalized.endsWith(")")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        if ("null".equalsIgnoreCase(normalized)) {
            return "";
        }
        if (normalized.length() >= 2 && normalized.startsWith("'") && normalized.endsWith("'")) {
            normalized = normalized.substring(1, normalized.length() - 1).replace("''", "'");
        } else if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private String describeDefault(String value) {
        String normalized = normalizeDefault(value);
        return normalized.isEmpty() ? "<none>" : normalized;
    }

    private boolean columnTypeMatches(String expectedType, ExistingColumn existing) {
        SqlType expected = parseSqlType(expectedType);
        String actualBase = normalizeTypeName(existing.typeName());
        if (!expected.base().equals(actualBase)) {
            return false;
        }
        if (("VARCHAR".equals(expected.base()) || "CHAR".equals(expected.base()))
                && !expected.params().isEmpty()) {
            return expected.params().get(0) == existing.columnSize();
        }
        if ("DECIMAL".equals(expected.base()) && expected.params().size() == 2) {
            return expected.params().get(0) == existing.columnSize()
                    && expected.params().get(1) == Math.max(existing.decimalDigits(), 0);
        }
        if (("DATETIME".equals(expected.base()) || "TIME".equals(expected.base())
                || "TIMESTAMP".equals(expected.base())) && !expected.params().isEmpty()) {
            return expected.params().get(0) == Math.max(existing.decimalDigits(), 0);
        }
        return true;
    }

    private SqlType parseSqlType(String type) {
        String trimmed = type.trim();
        int open = trimmed.indexOf('(');
        if (open < 0) {
            return new SqlType(normalizeTypeName(trimmed), List.of());
        }
        int close = trimmed.indexOf(')', open);
        String base = normalizeTypeName(trimmed.substring(0, open));
        if (close < 0) {
            return new SqlType(base, List.of());
        }
        List<Integer> params = new ArrayList<>();
        for (String part : trimmed.substring(open + 1, close).split(",")) {
            try {
                params.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                return new SqlType(base, List.of());
            }
        }
        return new SqlType(base, params);
    }

    private String normalizeTypeName(String typeName) {
        String normalized = typeName.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INTEGER" -> "INT";
            case "BOOL", "BOOLEAN" -> "TINYINT";
            default -> normalized;
        };
    }

    private boolean indexMatches(RdbIndexMetadata expected, ExistingIndex actual) {
        return expected.unique() == actual.unique
                && expected.columns().stream().map(column -> column.toLowerCase(Locale.ROOT)).toList()
                .equals(actual.columns().stream().map(column -> column.toLowerCase(Locale.ROOT)).toList());
    }

    private String describeIndex(RdbIndexMetadata index) {
        return (index.unique() ? "unique " : "non-unique ") + index.columns();
    }

    private String manualMigration(String message) {
        return "-- MANUAL MIGRATION REQUIRED: " + message;
    }

    private String quoted(String identifier) {
        return MysqlDialect.quoteIdentifier(identifier);
    }

    // ==================== 数据库元数据查询 ====================

    private boolean tableExists(Connection conn, String tableName) throws Exception {
        DatabaseMetaData dbMeta = conn.getMetaData();
        try (ResultSet rs = dbMeta.getTables(null, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private Map<String, ExistingColumn> getExistingColumnMetadata(Connection conn, String tableName) throws Exception {
        Map<String, ExistingColumn> columns = new LinkedHashMap<>();
        DatabaseMetaData dbMeta = conn.getMetaData();
        try (ResultSet rs = dbMeta.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                ExistingColumn column = new ExistingColumn(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getString("COLUMN_DEF"),
                        rs.getInt("NULLABLE"));
                columns.put(column.name().toLowerCase(Locale.ROOT), column);
            }
        }
        return columns;
    }

    private Map<String, ExistingIndex> getExistingIndexMetadata(Connection conn, String tableName) throws Exception {
        Map<String, ExistingIndex> indexes = new LinkedHashMap<>();
        DatabaseMetaData dbMeta = conn.getMetaData();
        try (ResultSet rs = dbMeta.getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                String indexKey = indexName.toLowerCase(Locale.ROOT);
                ExistingIndex index = indexes.get(indexKey);
                if (index == null) {
                    index = new ExistingIndex(indexName, !rs.getBoolean("NON_UNIQUE"));
                    indexes.put(indexKey, index);
                }
                index.addColumn(rs.getShort("ORDINAL_POSITION"), columnName);
            }
        }
        indexes.values().forEach(ExistingIndex::sortColumns);
        return indexes;
    }

    private record ExistingColumn(String name, String typeName, int columnSize, int decimalDigits,
            String columnDef, int nullable) {

        private String typeDescription() {
            String base = typeName.trim().toUpperCase(Locale.ROOT);
            return switch (base) {
                case "VARCHAR", "CHAR" -> base + "(" + columnSize + ")";
                case "DECIMAL" -> base + "(" + columnSize + "," + Math.max(decimalDigits, 0) + ")";
                case "DATETIME", "TIME", "TIMESTAMP" -> decimalDigits > 0
                        ? base + "(" + decimalDigits + ")"
                        : base;
                default -> base;
            };
        }
    }

    private record SqlType(String base, List<Integer> params) {
    }

    private record IndexColumn(short ordinal, String name) {
    }

    private static final class ExistingIndex {
        private final String name;
        private final boolean unique;
        private final List<IndexColumn> columns = new ArrayList<>();

        private ExistingIndex(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }

        private void addColumn(short ordinal, String column) {
            columns.add(new IndexColumn(ordinal, column));
        }

        private void sortColumns() {
            columns.sort(Comparator.comparingInt(IndexColumn::ordinal));
        }

        private List<String> columns() {
            return columns.stream().map(IndexColumn::name).toList();
        }

        private String describe() {
            return (unique ? "unique " : "non-unique ") + columns();
        }
    }
}
