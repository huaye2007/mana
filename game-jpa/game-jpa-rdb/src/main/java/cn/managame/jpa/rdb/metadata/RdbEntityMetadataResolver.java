package cn.managame.jpa.rdb.metadata;

import cn.managame.jpa.core.annotation.RoleId;
import cn.managame.jpa.core.exception.MetadataException;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metadata.ReflectionUtils;
import cn.managame.jpa.rdb.annotation.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RDB entity metadata resolver.
 */
public class RdbEntityMetadataResolver implements EntityMetadataResolver<RdbEntityMetadata> {

    @Override
    public boolean supports(Class<?> entityClass) {
        return entityClass.isAnnotationPresent(Entity.class);
    }

    @Override
    public RdbEntityMetadata resolve(Class<?> entityClass) {
        if (!supports(entityClass)) {
            throw new MetadataException("Class " + entityClass.getName() + " is not annotated with @Entity");
        }

        String tableName = resolveTableName(entityClass);
        List<RdbFieldMetadata> fields = new ArrayList<>();
        RdbFieldMetadata idField = null;
        RdbFieldMetadata versionField = null;
        RdbFieldMetadata shardKeyField = null;
        RdbFieldMetadata roleIdField = null;

        for (Field field : ReflectionUtils.getAllFields(entityClass)) {
            if (!ReflectionUtils.isPersistentField(field) || field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            // 字段必须显式声明 @Column 才会映射为列；未声明（含 synthetic 字段如 this$0）一律忽略。
            // @Id/@Version/@ShardKey/@RoleId 是行为标记，仍需与 @Column 同时声明。
            Column column = field.getAnnotation(Column.class);
            if (column == null) {
                continue;
            }
            boolean isId = field.isAnnotationPresent(Id.class);
            boolean isVersion = field.isAnnotationPresent(Version.class);
            boolean isShardKey = field.isAnnotationPresent(ShardKey.class);
            boolean isRoleId = field.isAnnotationPresent(RoleId.class);

            String columnName = !column.name().isEmpty() ? column.name() : field.getName();
            int length = column.length();
            String defaultValue = column.defaultValue();

            // 解析 @Column(type)：空 → 按 Java 类型推断（复杂类型默认 JSON）；
            // "json" → 逻辑 JSON 列；其余 → 原样 DDL 类型覆盖（值绑定仍走 converter）。
            String type = column.type().trim();
            boolean isJson;
            String sqlType;
            if (type.isEmpty()) {
                isJson = RdbTypes.isComplex(field.getType());
                sqlType = "";
            } else if (type.equalsIgnoreCase(ColumnType.JSON)) {
                isJson = true;
                sqlType = "";
            } else {
                isJson = false;
                sqlType = type;
            }

            RdbFieldMetadata fieldMeta = new RdbFieldMetadata(
                    field, field.getName(), columnName, field.getType(),
                    isId, length, defaultValue, isVersion, isJson, sqlType, isShardKey, isRoleId
            );

            fields.add(fieldMeta);

            if (isId) {
                if (idField != null) {
                    throw new MetadataException("Multiple @Id fields in " + entityClass.getName());
                }
                idField = fieldMeta;
            }
            if (isVersion) {
                if (versionField != null) {
                    throw new MetadataException("Multiple @Version fields in " + entityClass.getName());
                }
                versionField = fieldMeta;
            }
            if (isShardKey) {
                if (shardKeyField != null) {
                    throw new MetadataException("Multiple @ShardKey fields in " + entityClass.getName());
                }
                shardKeyField = fieldMeta;
            }
            if (isRoleId) {
                if (roleIdField != null) {
                    throw new MetadataException("Multiple @RoleId fields in " + entityClass.getName());
                }
                roleIdField = fieldMeta;
            }
        }

        if (idField == null) {
            throw new MetadataException("No @Id field found in " + entityClass.getName());
        }

        List<RdbIndexMetadata> indexes = resolveIndexes(entityClass);
        return new RdbEntityMetadata(entityClass, tableName, idField, fields, indexes,
                versionField, shardKeyField, roleIdField, resolveDataSource(entityClass));
    }

    private String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        return (table != null) ? table.name() : entityClass.getSimpleName().toLowerCase(Locale.ROOT);
    }

    private String resolveDataSource(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        return (table != null && !table.dataSource().isEmpty()) ? table.dataSource() : "default";
    }

    private List<RdbIndexMetadata> resolveIndexes(Class<?> entityClass) {
        List<RdbIndexMetadata> result = new ArrayList<>();
        Index[] indexes = entityClass.getAnnotationsByType(Index.class);
        for (Index idx : indexes) {
            result.add(new RdbIndexMetadata(idx.name(), List.of(idx.columns()), idx.unique()));
        }
        return result;
    }
}
