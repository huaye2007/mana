package com.github.huaye2007.mana.jpa.docdb.metadata;

import com.github.huaye2007.mana.jpa.core.bootstrap.ModelType;
import com.github.huaye2007.mana.jpa.core.bootstrap.ModelTypes;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;

import java.util.List;

/**
 * 文档实体元数据。
 */
public class DocEntityMetadata implements EntityMetadata {

    private final Class<?> entityType;
    private final String collectionName;
    private final DocFieldMetadata idField;
    private final List<DocFieldMetadata> fields;
    private final List<DocFieldMetadata> nonIdFields;
    private final DocFieldMetadata shardKeyField;
    private final DocFieldMetadata roleIdField;
    private final String dataSourceName;

    public DocEntityMetadata(Class<?> entityType, String collectionName,
                             DocFieldMetadata idField, List<DocFieldMetadata> fields) {
        this(entityType, collectionName, idField, fields, null);
    }

    public DocEntityMetadata(Class<?> entityType, String collectionName,
                             DocFieldMetadata idField, List<DocFieldMetadata> fields,
                             DocFieldMetadata shardKeyField) {
        this(entityType, collectionName, idField, fields, shardKeyField, null);
    }

    public DocEntityMetadata(Class<?> entityType, String collectionName,
                             DocFieldMetadata idField, List<DocFieldMetadata> fields,
                             DocFieldMetadata shardKeyField, DocFieldMetadata roleIdField) {
        this(entityType, collectionName, idField, fields, shardKeyField, roleIdField, "default");
    }

    public DocEntityMetadata(Class<?> entityType, String collectionName,
                             DocFieldMetadata idField, List<DocFieldMetadata> fields,
                             DocFieldMetadata shardKeyField, DocFieldMetadata roleIdField,
                             String dataSourceName) {
        this.entityType = entityType;
        this.collectionName = collectionName;
        this.idField = idField;
        this.fields = List.copyOf(fields);
        this.nonIdFields = fields.stream().filter(f -> !f.isPrimaryKey()).toList();
        this.shardKeyField = shardKeyField;
        this.roleIdField = roleIdField;
        this.dataSourceName = (dataSourceName == null || dataSourceName.isEmpty()) ? "default" : dataSourceName;
    }

    @Override
    public Class<?> entityType() { return entityType; }

    @Override
    public ModelType modelType() { return ModelTypes.DOCDB; }

    @Override
    public String logicalName() { return collectionName; }

    @Override
    public String dataSourceName() { return dataSourceName; }

    public String collectionName() { return collectionName; }

    public DocFieldMetadata idField() { return idField; }

    public List<DocFieldMetadata> fields() { return fields; }

    /** 已缓存，不再每次 stream */
    public List<DocFieldMetadata> nonIdFields() { return nonIdFields; }

    public List<DocFieldMetadata> indexedFields() {
        return fields.stream().filter(DocFieldMetadata::isIndexed).toList();
    }

    public DocFieldMetadata fieldByPropertyName(String propertyName) {
        for (DocFieldMetadata field : fields) {
            if (field.propertyName().equals(propertyName)) {
                return field;
            }
        }
        return null;
    }

    public DocFieldMetadata fieldByDocumentFieldName(String documentFieldName) {
        for (DocFieldMetadata field : fields) {
            if (field.documentFieldName().equals(documentFieldName)
                    || (field.isPrimaryKey() && "_id".equals(documentFieldName))) {
                return field;
            }
        }
        return null;
    }

    public DocFieldMetadata shardKeyField() { return shardKeyField; }

    public boolean hasShardKey() { return shardKeyField != null; }

    public DocFieldMetadata roleIdField() { return roleIdField; }

    public boolean hasRoleId() { return roleIdField != null; }
}
