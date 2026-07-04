package cn.managame.jpa.rdb.annotation;

/**
 * {@link Column#type()} 的标准取值常量。
 * <p>
 * 取值分两类：
 * <ul>
 *   <li>{@link #JSON} —— 框架可识别的<b>逻辑类型</b>：触发 Java 对象 ↔ JSON 字符串序列化，
 *       并由具体方言映射到物理列类型（MySQL → JSON）。</li>
 *   <li>其余 —— 原样写入 DDL 的<b>列类型名</b>，长度统一由 {@link Column#length()} 提供，
 *       值绑定仍走 {@code TypeConverterRegistry}（配合自定义 {@code TypeConverter} 支持自定义类型）。</li>
 * </ul>
 */
public final class ColumnType {

    /** 逻辑类型：JSON 列，触发对象 ↔ JSON 字符串序列化。 */
    public static final String JSON = "json";

    // 字符串
    public static final String VARCHAR = "VARCHAR";
    public static final String CHAR = "CHAR";
    public static final String TEXT = "TEXT";
    public static final String TINYTEXT = "TINYTEXT";
    public static final String MEDIUMTEXT = "MEDIUMTEXT";
    public static final String LONGTEXT = "LONGTEXT";

    // 二进制
    public static final String BINARY = "BINARY";
    public static final String VARBINARY = "VARBINARY";
    public static final String BLOB = "BLOB";
    public static final String TINYBLOB = "TINYBLOB";
    public static final String MEDIUMBLOB = "MEDIUMBLOB";
    public static final String LONGBLOB = "LONGBLOB";

    // 数值
    public static final String TINYINT = "TINYINT";
    public static final String SMALLINT = "SMALLINT";
    public static final String INT = "INT";
    public static final String BIGINT = "BIGINT";
    public static final String FLOAT = "FLOAT";
    public static final String DOUBLE = "DOUBLE";
    public static final String DECIMAL = "DECIMAL";

    // 时间
    public static final String DATE = "DATE";
    public static final String TIME = "TIME";
    public static final String DATETIME = "DATETIME";
    public static final String TIMESTAMP = "TIMESTAMP";

    private ColumnType() {
    }
}
