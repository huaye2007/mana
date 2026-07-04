package cn.managame.jpa.rdb.mysql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Java 类型到 MySQL 列类型的映射。
 */
public class MysqlTypeMapping {

    private static final Map<Class<?>, String> TYPE_MAP = Map.ofEntries(
            Map.entry(byte.class, "TINYINT"),
            Map.entry(Byte.class, "TINYINT"),
            Map.entry(short.class, "SMALLINT"),
            Map.entry(Short.class, "SMALLINT"),
            Map.entry(int.class, "INT"),
            Map.entry(Integer.class, "INT"),
            Map.entry(long.class, "BIGINT"),
            Map.entry(Long.class, "BIGINT"),
            Map.entry(float.class, "FLOAT"),
            Map.entry(Float.class, "FLOAT"),
            Map.entry(double.class, "DOUBLE"),
            Map.entry(Double.class, "DOUBLE"),
            Map.entry(boolean.class, "TINYINT(1)"),
            Map.entry(Boolean.class, "TINYINT(1)"),
            Map.entry(String.class, "VARCHAR"),
            Map.entry(Instant.class, "DATETIME(6)"),
            Map.entry(LocalDateTime.class, "DATETIME(6)"),
            Map.entry(LocalDate.class, "DATE"),
            Map.entry(LocalTime.class, "TIME(6)"),
            Map.entry(BigDecimal.class, "DECIMAL(38,10)"),
            Map.entry(BigInteger.class, "DECIMAL(65,0)"),
            Map.entry(UUID.class, "CHAR(36)")
    );

    /** 需要追加 {@code (length)} 的类型。 */
    private static final Set<String> LENGTH_TYPES = Set.of("VARCHAR", "CHAR", "VARBINARY", "BINARY");

    /**
     * 获取 MySQL 列类型，优先级：显式 override &gt; JSON &gt; 按 Java 类型推断。
     *
     * @param javaType Java 类型
     * @param length   长度（对 VARCHAR/CHAR 等长度类型有效）
     * @param isJson   是否为 JSON 逻辑列
     * @param override {@code @Column(type=...)} 的裸类型名覆盖，空串表示无覆盖
     */
    public static String toSqlType(Class<?> javaType, int length, boolean isJson, String override) {
        if (override != null && !override.isEmpty()) {
            return applyLength(override, length);
        }
        if (isJson) {
            return "JSON";
        }
        if (javaType.isEnum()) {
            return "VARCHAR(" + Math.max(length, 32) + ")";
        }
        if (javaType == byte[].class || javaType == Byte[].class) {
            return "BLOB";
        }
        String sqlType = TYPE_MAP.get(javaType);
        if (sqlType == null) {
            return "TEXT";
        }
        if ("VARCHAR".equals(sqlType)) {
            return "VARCHAR(" + length + ")";
        }
        return sqlType;
    }

    /**
     * 为长度类型拼接 {@code (length)}；裸类型名统一大写，已带括号参数的取值原样透传。
     */
    private static String applyLength(String typeName, int length) {
        String trimmed = typeName.trim();
        if (trimmed.indexOf('(') >= 0) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        String base = trimmed.toUpperCase(Locale.ROOT);
        return LENGTH_TYPES.contains(base) ? base + "(" + length + ")" : base;
    }
}
