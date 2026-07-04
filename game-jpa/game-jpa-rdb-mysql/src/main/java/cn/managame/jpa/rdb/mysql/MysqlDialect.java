package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbFieldMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MySQL 方言：负责 SQL 模板生成。
 * QuerySpec 中的属性名会通过元数据自动转换为数据库列名。
 * <p>
 * 简单模板 SQL（selectById/selectAll/insert/update/deleteById）按
 * metadata + tableName 缓存，避免重复字符串拼接。
 * 动态查询（buildQuery/buildCount）因参数不同不适合缓存。
 */
public class MysqlDialect {

    private static final String MYSQL_UNBOUNDED_LIMIT = "18446744073709551615";

    /** 合法 SQL 标识符模式，预编译避免 buildQuery/buildCount 动态路径每次重编译。 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** SQL 模板缓存：key = metadata.entityType + tableName + operation */
    private final ConcurrentHashMap<String, String> sqlCache = new ConcurrentHashMap<>();

    public String selectById(RdbEntityMetadata meta) {
        return selectById(meta, meta.tableName());
    }

    public String selectById(RdbEntityMetadata meta, String tableName) {
        return sqlCache.computeIfAbsent(
                cacheKey(meta, tableName, "selectById"),
                k -> buildSelectById(meta, tableName));
    }

    public String selectAll(RdbEntityMetadata meta) {
        return selectAll(meta, meta.tableName());
    }

    public String selectAll(RdbEntityMetadata meta, String tableName) {
        return sqlCache.computeIfAbsent(
                cacheKey(meta, tableName, "selectAll"),
                k -> buildSelectAll(meta, tableName));
    }

    public String insert(RdbEntityMetadata meta) {
        return insert(meta, meta.tableName());
    }

    public String insert(RdbEntityMetadata meta, String tableName) {
        return sqlCache.computeIfAbsent(
                cacheKey(meta, tableName, "insert"),
                k -> buildInsert(meta, tableName));
    }

    public String update(RdbEntityMetadata meta) {
        return update(meta, meta.tableName());
    }

    public String update(RdbEntityMetadata meta, String tableName) {
        return sqlCache.computeIfAbsent(
                cacheKey(meta, tableName, "update"),
                k -> buildUpdate(meta, tableName));
    }

    public String deleteById(RdbEntityMetadata meta) {
        return deleteById(meta, meta.tableName());
    }

    public String deleteById(RdbEntityMetadata meta, String tableName) {
        return sqlCache.computeIfAbsent(
                cacheKey(meta, tableName, "deleteById"),
                k -> buildDeleteById(meta, tableName));
    }

    /**
     * 生成 MySQL 原子 upsert SQL：INSERT ... ON DUPLICATE KEY UPDATE ...
     * 用于替代 DefaultRdbRepository.save() 的 check-then-act，消除并发主键冲突（Bug-8）。
     * 所有非 ID 字段在冲突时都会被 UPDATE，version 字段会 +1。
     */
    public String upsert(RdbEntityMetadata meta, String tableName) {
        return sqlCache.computeIfAbsent(
                cacheKey(meta, tableName, "upsert"),
                k -> buildUpsert(meta, tableName));
    }

    public String upsert(RdbEntityMetadata meta) {
        return upsert(meta, meta.tableName());
    }

    public String buildQuery(RdbEntityMetadata meta, RdbQuerySpec spec) {
        return buildQuery(meta, spec, meta.tableName());
    }

    public String buildQuery(RdbEntityMetadata meta, RdbQuerySpec spec, String tableName) {
        String columns = meta.fields().stream()
                .map(field -> quoteIdentifier(field.columnName()))
                .collect(Collectors.joining(", "));
        StringBuilder sb = new StringBuilder("SELECT ")
                .append(columns).append(" FROM ").append(quoteIdentifier(tableName));

        if (!spec.conditions().isEmpty()) {
            sb.append(" WHERE ");
            StringJoiner joiner = new StringJoiner(" AND ");
            for (RdbQuerySpec.Condition cond : spec.conditions()) {
                String col = resolveColumnName(meta, cond.property());
                joiner.add(buildCondition(col, cond));
            }
            sb.append(joiner);
        }

        if (!spec.orderBys().isEmpty()) {
            sb.append(" ORDER BY ");
            StringJoiner joiner = new StringJoiner(", ");
            for (RdbQuerySpec.OrderBy order : spec.orderBys()) {
                String col = resolveColumnName(meta, order.property());
                joiner.add(col + (order.ascending() ? " ASC" : " DESC"));
            }
            sb.append(joiner);
        }

        if (spec.limit() > 0) {
            sb.append(" LIMIT ").append(spec.limit());
        } else if (spec.offset() >= 0) {
            sb.append(" LIMIT ").append(MYSQL_UNBOUNDED_LIMIT);
        }
        if (spec.offset() >= 0) {
            sb.append(" OFFSET ").append(spec.offset());
        }

        return sb.toString();
    }

    public String buildCount(RdbEntityMetadata meta, RdbQuerySpec spec) {
        return buildCount(meta, spec, meta.tableName());
    }

    public String buildCount(RdbEntityMetadata meta, RdbQuerySpec spec, String tableName) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(quoteIdentifier(tableName));
        if (!spec.conditions().isEmpty()) {
            sb.append(" WHERE ");
            StringJoiner joiner = new StringJoiner(" AND ");
            for (RdbQuerySpec.Condition cond : spec.conditions()) {
                String col = resolveColumnName(meta, cond.property());
                joiner.add(buildCondition(col, cond));
            }
            sb.append(joiner);
        }
        return sb.toString();
    }

    // ==================== 缓存 key ====================

    private String cacheKey(RdbEntityMetadata meta, String tableName, String operation) {
        return meta.entityType().getName() + ":" + tableName + ":" + operation;
    }

    // ==================== SQL 构建方法 ====================

    private String buildSelectById(RdbEntityMetadata meta, String tableName) {
        String columns = meta.fields().stream()
                .map(field -> quoteIdentifier(field.columnName()))
                .collect(Collectors.joining(", "));
        return "SELECT " + columns + " FROM " + quoteIdentifier(tableName)
                + " WHERE " + quoteIdentifier(meta.idField().columnName()) + " = ?";
    }

    private String buildSelectAll(RdbEntityMetadata meta, String tableName) {
        String columns = meta.fields().stream()
                .map(field -> quoteIdentifier(field.columnName()))
                .collect(Collectors.joining(", "));
        return "SELECT " + columns + " FROM " + quoteIdentifier(tableName);
    }

    private String buildInsert(RdbEntityMetadata meta, String tableName) {
        List<RdbFieldMetadata> fields = meta.fields();
        String columns = fields.stream().map(field -> quoteIdentifier(field.columnName())).collect(Collectors.joining(", "));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + quoteIdentifier(tableName) + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    private String buildUpdate(RdbEntityMetadata meta, String tableName) {
        String setClauses = meta.nonIdFields().stream()
                .map(f -> quoteIdentifier(f.columnName()) + " = ?")
                .collect(Collectors.joining(", "));
        String sql = "UPDATE " + quoteIdentifier(tableName) + " SET " + setClauses
                + " WHERE " + quoteIdentifier(meta.idField().columnName()) + " = ?";
        if (meta.hasVersion()) {
            sql += " AND " + quoteIdentifier(meta.versionField().columnName()) + " = ?";
        }
        return sql;
    }

    private String buildDeleteById(RdbEntityMetadata meta, String tableName) {
        return "DELETE FROM " + quoteIdentifier(tableName)
                + " WHERE " + quoteIdentifier(meta.idField().columnName()) + " = ?";
    }

    private String buildUpsert(RdbEntityMetadata meta, String tableName) {
        List<RdbFieldMetadata> fields = meta.fields();
        String columns = fields.stream().map(field -> quoteIdentifier(field.columnName())).collect(Collectors.joining(", "));
        String placeholders = fields.stream().map(f -> "?").collect(Collectors.joining(", "));
        // ON DUPLICATE KEY UPDATE：先生成所有非 version 列，再把 version 自增子句追加到最后。
        // MySQL 的 ON DUPLICATE KEY UPDATE 从左到右求值，且后面的子句能看到前面已更新的值。
        // 若 version 自增先执行，其它列的 IF(version = ?, ...) 会拿到自增后的版本号，比较失败、
        // 静默跳过更新而只把版本号 +1，导致这次写入悄无声息地丢失。因此 version 子句必须排在最后，
        // 与字段在实体中的声明顺序（反射顺序不保证）解耦。
        StringJoiner updateClauses = new StringJoiner(", ");
        for (RdbFieldMetadata field : meta.nonIdFields()) {
            if (!field.isVersionField()) {
                updateClauses.add(buildUpsertUpdateClause(meta, field));
            }
        }
        if (meta.hasVersion()) {
            updateClauses.add(buildVersionUpsertClause(meta, meta.versionField()));
        }
        return "INSERT INTO " + quoteIdentifier(tableName) + " (" + columns + ") VALUES (" + placeholders + ")"
                + " ON DUPLICATE KEY UPDATE " + updateClauses;
    }

    private String buildUpsertUpdateClause(RdbEntityMetadata meta, RdbFieldMetadata field) {
        String column = quoteIdentifier(field.columnName());
        if (!meta.hasVersion()) {
            return column + " = ?";
        }
        String versionColumn = quoteIdentifier(meta.versionField().columnName());
        return column + " = IF(" + versionColumn + " = ?, ?, " + column + ")";
    }

    private String buildVersionUpsertClause(RdbEntityMetadata meta, RdbFieldMetadata field) {
        String column = quoteIdentifier(field.columnName());
        if (!meta.hasVersion()) {
            return column + " = " + column + " + 1";
        }
        return column + " = IF(" + column + " = ?, " + column + " + 1, " + column + ")";
    }

    // ==================== 辅助方法 ====================

    /**
     * 将 Java 属性名解析为数据库列名。
     * 找不到时原样返回（兼容直接传列名的旧用法）。
     */
    private String resolveColumnName(RdbEntityMetadata meta, String propertyName) {
        RdbFieldMetadata field = meta.fieldByPropertyName(propertyName);
        if (field == null) {
            throw new IllegalArgumentException("Unknown query property: " + propertyName
                    + " for entity " + meta.entityType().getName());
        }
        return quoteIdentifier(field.columnName());
    }

    private String buildCondition(String columnName, RdbQuerySpec.Condition cond) {
        return switch (cond.operator()) {
            case EQ -> columnName + " = ?";
            case NE -> columnName + " != ?";
            case GT -> columnName + " > ?";
            case GTE -> columnName + " >= ?";
            case LT -> columnName + " < ?";
            case LTE -> columnName + " <= ?";
            case IN -> {
                int size = (cond.value() instanceof Collection<?> c) ? c.size() : 1;
                if (size <= 0) {
                    throw new IllegalArgumentException("IN values must not be empty: " + cond.property());
                }
                String placeholders = "?,".repeat(Math.max(0, size));
                if (placeholders.endsWith(",")) {
                    placeholders = placeholders.substring(0, placeholders.length() - 1);
                }
                yield columnName + " IN (" + placeholders + ")";
            }
        };
    }

    static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("SQL identifier must not be empty");
        }
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
