package com.github.huaye2007.mana.jpa.docdb.metadata;

import com.github.huaye2007.mana.jpa.core.metadata.FieldAccessor;
import com.github.huaye2007.mana.jpa.core.metadata.FieldMetadata;

import java.lang.reflect.Field;

/**
 * 文档字段元数据。
 */
public class DocFieldMetadata implements FieldMetadata {

    private final Field javaField;
    private final String propertyName;
    private final String documentFieldName;
    private final Class<?> javaType;
    private final boolean primaryKey;
    private final boolean embedded;
    private final boolean indexed;
    private final boolean roleId;
    private final FieldAccessor accessor;

    public DocFieldMetadata(Field javaField, String propertyName,
                            String documentFieldName, Class<?> javaType,
                            boolean primaryKey, boolean embedded, boolean indexed) {
        this(javaField, propertyName, documentFieldName, javaType, primaryKey, embedded, indexed, false);
    }

    public DocFieldMetadata(Field javaField, String propertyName,
                            String documentFieldName, Class<?> javaType,
                            boolean primaryKey, boolean embedded, boolean indexed, boolean roleId) {
        this.javaField = javaField;
        this.propertyName = propertyName;
        this.documentFieldName = documentFieldName;
        this.javaType = javaType;
        this.primaryKey = primaryKey;
        this.embedded = embedded;
        this.indexed = indexed;
        this.roleId = roleId;
        this.accessor = new FieldAccessor(javaField);
    }

    @Override
    public Field javaField() { return javaField; }

    @Override
    public String propertyName() { return propertyName; }

    @Override
    public String storageName() { return documentFieldName; }

    @Override
    public Class<?> javaType() { return javaType; }

    @Override
    public FieldAccessor accessor() { return accessor; }

    public String documentFieldName() { return documentFieldName; }

    public boolean isPrimaryKey() { return primaryKey; }

    public boolean isEmbedded() { return embedded; }

    public boolean isIndexed() { return indexed; }

    public boolean isRoleId() { return roleId; }
}
