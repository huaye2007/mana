package cn.managame.jpa.rdb.metadata;

import cn.managame.jpa.core.bootstrap.ModelType;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.metadata.EntityMetadata;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 关系型实体元数据。
 */
public class RdbEntityMetadata implements EntityMetadata {

    private final Class<?> entityType;
    private final String tableName;
    private final RdbFieldMetadata idField;
    private final List<RdbFieldMetadata> fields;
    private final List<RdbFieldMetadata> nonIdFields;
    private final List<RdbIndexMetadata> indexes;
    private final RdbFieldMetadata versionField;
    private final RdbFieldMetadata shardKeyField;
    private final RdbFieldMetadata roleIdField;
    private final String dataSourceName;
    private final Map<String, RdbFieldMetadata> propertyNameMap;

    public RdbEntityMetadata(Class<?> entityType, String tableName,
                             RdbFieldMetadata idField, List<RdbFieldMetadata> fields,
                             List<RdbIndexMetadata> indexes, RdbFieldMetadata versionField,
                             RdbFieldMetadata shardKeyField) {
        this(entityType, tableName, idField, fields, indexes, versionField, shardKeyField, null);
    }

    public RdbEntityMetadata(Class<?> entityType, String tableName,
                             RdbFieldMetadata idField, List<RdbFieldMetadata> fields,
                             List<RdbIndexMetadata> indexes, RdbFieldMetadata versionField,
                             RdbFieldMetadata shardKeyField, RdbFieldMetadata roleIdField) {
        this(entityType, tableName, idField, fields, indexes, versionField, shardKeyField, roleIdField, "default");
    }

    public RdbEntityMetadata(Class<?> entityType, String tableName,
                             RdbFieldMetadata idField, List<RdbFieldMetadata> fields,
                             List<RdbIndexMetadata> indexes, RdbFieldMetadata versionField,
                             RdbFieldMetadata shardKeyField, RdbFieldMetadata roleIdField,
                             String dataSourceName) {
        this.entityType = entityType;
        this.tableName = tableName;
        this.idField = idField;
        this.fields = List.copyOf(fields);
        this.nonIdFields = fields.stream().filter(f -> !f.isPrimaryKey()).toList();
        this.indexes = List.copyOf(indexes);
        this.versionField = versionField;
        this.shardKeyField = shardKeyField;
        this.roleIdField = roleIdField;
        this.dataSourceName = (dataSourceName == null || dataSourceName.isEmpty()) ? "default" : dataSourceName;
        this.propertyNameMap = fields.stream()
                .collect(Collectors.toUnmodifiableMap(RdbFieldMetadata::propertyName, f -> f));
    }

    /**
     * 向后兼容的构造器（不含 shardKeyField）
     */
    public RdbEntityMetadata(Class<?> entityType, String tableName,
                             RdbFieldMetadata idField, List<RdbFieldMetadata> fields,
                             List<RdbIndexMetadata> indexes, RdbFieldMetadata versionField) {
        this(entityType, tableName, idField, fields, indexes, versionField, null);
    }

    @Override
    public Class<?> entityType() { return entityType; }

    @Override
    public ModelType modelType() { return ModelTypes.RDB; }

    @Override
    public String logicalName() { return tableName; }

    @Override
    public String dataSourceName() { return dataSourceName; }

    public String tableName() { return tableName; }

    public RdbFieldMetadata idField() { return idField; }

    public List<RdbFieldMetadata> fields() { return fields; }

    /** 已缓存，不再每次 stream */
    public List<RdbFieldMetadata> nonIdFields() { return nonIdFields; }

    public List<RdbIndexMetadata> indexes() { return indexes; }

    public RdbFieldMetadata versionField() { return versionField; }

    public boolean hasVersion() { return versionField != null; }

    public RdbFieldMetadata shardKeyField() { return shardKeyField; }

    public boolean hasShardKey() { return shardKeyField != null; }

    public RdbFieldMetadata roleIdField() { return roleIdField; }

    public boolean hasRoleId() { return roleIdField != null; }

    /**
     * 按属性名查找字段元数据（用于 QuerySpec 属性名→列名转换）
     */
    public RdbFieldMetadata fieldByPropertyName(String propertyName) {
        return propertyNameMap.get(propertyName);
    }
}
