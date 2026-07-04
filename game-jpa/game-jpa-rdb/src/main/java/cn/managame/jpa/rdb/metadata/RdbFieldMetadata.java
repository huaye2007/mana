package cn.managame.jpa.rdb.metadata;

import cn.managame.jpa.core.metadata.FieldAccessor;
import cn.managame.jpa.core.metadata.FieldMetadata;

import java.lang.reflect.Field;

/**
 * 关系型字段元数据。
 */
public class RdbFieldMetadata implements FieldMetadata {

    private final Field javaField;
    private final String propertyName;
    private final String columnName;
    private final Class<?> javaType;
    private final boolean primaryKey;
    private final int length;
    private final String defaultValue;
    private final boolean versionField;
    private final boolean jsonField;
    private final String sqlType;
    private final boolean shardKey;
    private final boolean roleId;
    private final FieldAccessor accessor;

    public RdbFieldMetadata(Field javaField, String propertyName, String columnName,
                            Class<?> javaType, boolean primaryKey, int length, String defaultValue,
                            boolean versionField, boolean jsonField, String sqlType,
                            boolean shardKey, boolean roleId) {
        this.javaField = javaField;
        this.propertyName = propertyName;
        this.columnName = columnName;
        this.javaType = javaType;
        this.primaryKey = primaryKey;
        this.length = length;
        this.defaultValue = defaultValue != null ? defaultValue : "";
        this.versionField = versionField;
        this.jsonField = jsonField;
        this.sqlType = sqlType != null ? sqlType : "";
        this.shardKey = shardKey;
        this.roleId = roleId;
        this.accessor = new FieldAccessor(javaField);
    }

    @Override
    public Field javaField() { return javaField; }

    @Override
    public String propertyName() { return propertyName; }

    @Override
    public String storageName() { return columnName; }

    @Override
    public Class<?> javaType() { return javaType; }

    @Override
    public FieldAccessor accessor() { return accessor; }

    public String columnName() { return columnName; }

    public boolean isPrimaryKey() { return primaryKey; }

    public int length() { return length; }

    public String defaultValue() { return defaultValue; }

    public boolean isVersionField() { return versionField; }

    public boolean isJsonField() { return jsonField; }

    /**
     * 显式声明的 DDL 列类型覆盖（{@code @Column(type=...)} 的非 JSON 取值），无则为空串。
     */
    public String sqlType() { return sqlType; }

    public boolean isShardKey() { return shardKey; }

    public boolean isRoleId() { return roleId; }
}
