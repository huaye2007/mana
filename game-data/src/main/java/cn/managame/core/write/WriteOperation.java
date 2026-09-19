package cn.managame.core.write;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.metadata.EntityMetadata;

import java.util.Objects;

/**
 * Lightweight pending write. INSERT/UPDATE retain only the live entity reference.
 * Field values are read by DataAccess when the table writer flushes; no entity snapshot is created.
 */
public final class WriteOperation<T> {
    private final WriteType type;
    private final EntityMetadata<T> metadata;
    private final T entity;
    private final Object id;
    private final Object groupKey;
    private final String physicalName;

    private WriteOperation(
            WriteType type,
            EntityMetadata<T> metadata,
            T entity,
            Object id,
            Object groupKey,
            String physicalName) {
        this.type = Objects.requireNonNull(type);
        this.metadata = Objects.requireNonNull(metadata);
        this.entity = entity;
        this.id = id;
        this.groupKey = groupKey;
        this.physicalName = physicalName == null || physicalName.isBlank() ? null : physicalName;
        switch (type) {
            case INSERT, UPDATE -> {
                Objects.requireNonNull(entity, type + " requires entity");
                Objects.requireNonNull(id, type + " requires id");
            }
            case DELETE -> Objects.requireNonNull(id, "DELETE requires id");
            case DELETE_GROUP -> Objects.requireNonNull(groupKey, "DELETE_GROUP requires groupKey");
        }
    }

    public static <T> WriteOperation<T> insert(EntityMetadata<T> metadata, T entity, Object id, Object groupKey) {
        return new WriteOperation<>(WriteType.INSERT, metadata, entity, id, groupKey, null);
    }

    public static <T> WriteOperation<T> insert(EntityMetadata<T> metadata, T entity, Object id, Object groupKey, String physicalName) {
        return new WriteOperation<>(WriteType.INSERT, metadata, entity, id, groupKey, physicalName);
    }

    public static <T> WriteOperation<T> update(EntityMetadata<T> metadata, T entity, Object id, Object groupKey) {
        return new WriteOperation<>(WriteType.UPDATE, metadata, entity, id, groupKey, null);
    }

    public static <T> WriteOperation<T> delete(EntityMetadata<T> metadata, Object id, Object groupKey) {
        return new WriteOperation<>(WriteType.DELETE, metadata, null, id, groupKey, null);
    }

    public static <T> WriteOperation<T> deleteGroup(EntityMetadata<T> metadata, Object groupKey) {
        return new WriteOperation<>(WriteType.DELETE_GROUP, metadata, null, null, groupKey, null);
    }

    public WriteType type() { return type; }
    public EntityMetadata<T> metadata() { return metadata; }
    public T entity() { return entity; }
    public Object id() { return id; }
    public Object groupKey() { return groupKey; }
    public String physicalName() { return physicalName; }

    WriteOperation<T> asInsert() {
        if (entity == null || id == null) throw new DataException("Cannot convert operation without entity/id to INSERT");
        return new WriteOperation<>(WriteType.INSERT, metadata, entity, id, groupKey, physicalName);
    }
}
