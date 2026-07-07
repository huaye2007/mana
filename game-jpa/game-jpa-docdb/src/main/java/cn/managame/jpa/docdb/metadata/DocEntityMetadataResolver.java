package cn.managame.jpa.docdb.metadata;

import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.core.annotation.RoleId;
import cn.managame.jpa.core.annotation.ShardKey;
import cn.managame.jpa.core.exception.MetadataException;
import cn.managame.jpa.core.metadata.EntityMetadataResolver;
import cn.managame.jpa.core.metadata.ReflectionUtils;
import cn.managame.jpa.docdb.annotation.*;
import cn.managame.jpa.docdb.annotation.Field;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档实体元数据解析器。
 */
public class DocEntityMetadataResolver implements EntityMetadataResolver<DocEntityMetadata> {

    @Override
    public boolean supports(Class<?> entityClass) {
        return entityClass.isAnnotationPresent(Document.class);
    }

    @Override
    public DocEntityMetadata resolve(Class<?> entityClass) {
        if (!supports(entityClass)) {
            throw new MetadataException("Class " + entityClass.getName() + " is not annotated with @Document");
        }

        Document doc = entityClass.getAnnotation(Document.class);
        List<DocFieldMetadata> fields = new ArrayList<>();
        DocFieldMetadata idField = null;
        DocFieldMetadata shardKeyField = null;
        DocFieldMetadata roleIdField = null;

        for (java.lang.reflect.Field field : ReflectionUtils.getAllFields(entityClass)) {
            if (!ReflectionUtils.isPersistentField(field)) continue;

            boolean isId = field.isAnnotationPresent(Id.class);
            // 嵌套文档按字段类型自动判定，业务 POJO 不需要注解；id 字段始终按标量/转换器处理
            boolean isEmbedded = !isId && DocTypes.isEmbeddedDocument(field.getType());
            boolean isIndexed = field.isAnnotationPresent(Indexed.class);
            boolean isShardKey = field.isAnnotationPresent(ShardKey.class);
            boolean isRoleId = field.isAnnotationPresent(RoleId.class);
            Field fieldAnn = field.getAnnotation(Field.class);

            String docFieldName = (fieldAnn != null && !fieldAnn.name().isEmpty()) ? fieldAnn.name() : field.getName();

            DocFieldMetadata meta = new DocFieldMetadata(
                    field, field.getName(), docFieldName, field.getType(),
                    isId, isEmbedded, isIndexed, isRoleId
            );

            fields.add(meta);

            if (isId) {
                if (idField != null) {
                    throw new MetadataException("Multiple @Id fields in " + entityClass.getName());
                }
                idField = meta;
            }
            if (isShardKey) {
                if (shardKeyField != null) {
                    throw new MetadataException("Multiple @ShardKey fields in " + entityClass.getName());
                }
                shardKeyField = meta;
            }
            if (isRoleId) {
                if (roleIdField != null) {
                    throw new MetadataException("Multiple @RoleId fields in " + entityClass.getName());
                }
                roleIdField = meta;
            }
        }

        if (idField == null) {
            throw new MetadataException("No @Id field found in " + entityClass.getName());
        }

        String dataSource = !doc.dataSource().isEmpty() ? doc.dataSource() : "default";
        return new DocEntityMetadata(entityClass, doc.collection(), idField, fields, shardKeyField,
                roleIdField, dataSource);
    }
}
