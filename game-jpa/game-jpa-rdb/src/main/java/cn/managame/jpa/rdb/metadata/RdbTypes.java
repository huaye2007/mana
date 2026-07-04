package cn.managame.jpa.rdb.metadata;

import java.time.temporal.Temporal;
import java.util.Date;
import java.util.UUID;

/**
 * RDB 字段 Java 类型分类（方言无关）。
 * <p>
 * 用于在未显式声明 {@code @Column(type=...)} 时推断字段映射：
 * <b>标量类型</b>有原生列类型，按 Java 类型直接映射；
 * <b>复杂类型</b>（集合 / Map / 数组 / 自定义 POJO）默认按 {@code ColumnType.JSON} 序列化存储。
 */
public final class RdbTypes {

    private RdbTypes() {
    }

    /**
     * 是否为标量类型（有原生列类型，不需要 JSON 序列化）。
     */
    public static boolean isScalar(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        if (type == byte[].class || type == Byte[].class) {
            return true;
        }
        if (type == Boolean.class || type == Character.class) {
            return true;
        }
        if (CharSequence.class.isAssignableFrom(type)) {       // String 等
            return true;
        }
        if (Number.class.isAssignableFrom(type)) {             // 包装数值 / BigDecimal / BigInteger
            return true;
        }
        if (Temporal.class.isAssignableFrom(type)) {           // Instant / LocalDate(Time) / LocalDateTime ...
            return true;
        }
        if (Date.class.isAssignableFrom(type)) {               // java.util.Date / java.sql.Date,Time,Timestamp
            return true;
        }
        return type == UUID.class;
    }

    /**
     * 是否为复杂类型（默认 JSON 存储）。
     */
    public static boolean isComplex(Class<?> type) {
        return !isScalar(type);
    }
}
