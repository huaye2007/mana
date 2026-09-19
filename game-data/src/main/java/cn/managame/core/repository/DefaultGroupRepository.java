package cn.managame.core.repository;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.cache.EntityCache;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.MetadataRegistry;
import cn.managame.core.write.WriteBehindEngine;
import cn.managame.core.write.WriteOperation;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Default complete-group cache implementation. */
public final class DefaultGroupRepository<T, ID> implements GroupRepository<T, ID> {
    private final EntityMetadata<T> metadata;
    private final DataAccess dataAccess;
    private final WriteBehindEngine writer;
    private final EntityCache<Object, Map<Object, T>> groups;

    public DefaultGroupRepository(
            Class<T> entityType,
            MetadataRegistry registry,
            DataAccess dataAccess,
            WriteBehindEngine writer,
            Duration cacheExpireAfterAccess,
            boolean ensureSchema) {
        this.metadata = registry.get(entityType);
        dataAccess.validateMapping(metadata);
        if (metadata.groupKeyProperties().isEmpty()) {
            throw new DataException(entityType.getName() + " must declare at least one @GroupKey field");
        }
        this.dataAccess = dataAccess;
        this.writer = writer;
        this.groups = new EntityCache<>(cacheExpireAfterAccess, metadata.resident());
        if (ensureSchema) dataAccess.ensureSchema(metadata);
        if (metadata.resident()) preload();
    }

    @Override
    public Map<Object, T> getGroup(Object groupKey) {
        requireGroupKey(groupKey);
        return groups.get(groupKey,
                key -> metadata.resident() ? new LinkedHashMap<>() : loadGroup(key));
    }

    @Override
    public void insert(T entity) {
        Objects.requireNonNull(entity, "entity");
        Object groupKey = groupKey(entity);
        Object mapKey = mapKey(entity);
        Object id = id(entity);
        Map<Object, T> current = requireLoadedGroup(groupKey, "insert");
        if (current.containsKey(mapKey)) {
            throw new DataException("Duplicate map key in loaded group: type=" + metadata.type().getName()
                    + ", groupKey=" + groupKey + ", mapKey=" + mapKey);
        }
        writer.submit(WriteOperation.insert(metadata, entity, id, groupKey));
        current.put(mapKey, entity);
    }

    @Override
    public void update(T entity) {
        Objects.requireNonNull(entity, "entity");
        Object groupKey = groupKey(entity);
        Object mapKey = mapKey(entity);
        Object id = id(entity);
        Map<Object, T> current = requireLoadedGroup(groupKey, "update");
        requireCachedEntity(current, mapKey, id);
        writer.submit(WriteOperation.update(metadata, entity, id, groupKey));
        current.put(mapKey, entity);
    }

    @Override
    public void delete(T entity) {
        Objects.requireNonNull(entity, "entity");
        Object groupKey = groupKey(entity);
        Object mapKey = mapKey(entity);
        Object id = id(entity);
        Map<Object, T> current = requireLoadedGroup(groupKey, "delete");
        requireCachedEntity(current, mapKey, id);
        writer.submit(WriteOperation.delete(metadata, id, groupKey));
        current.remove(mapKey);
    }

    @Override
    public void deleteGroup(Object groupKey) {
        requireGroupKey(groupKey);
        Map<Object, T> current = groups.getIfPresent(groupKey);
        writer.submit(WriteOperation.deleteGroup(metadata, groupKey));
        if (current != null) {
            current.clear();
        } else {
            groups.put(groupKey, new LinkedHashMap<>());
        }
    }

    @Override
    public boolean resident() { return metadata.resident(); }

    private Map<Object, T> loadGroup(Object key) {
        Map<Object, T> values = new LinkedHashMap<>();
        for (T entity : dataAccess.find(metadata, metadata.groupQuery(key))) {
            id(entity);
            Object mapKey = mapKey(entity);
            Object previous = values.putIfAbsent(mapKey, entity);
            if (previous != null) {
                throw new DataException("Duplicate map key loaded from database: type=" + metadata.type().getName()
                        + ", groupKey=" + key + ", mapKey=" + mapKey);
            }
        }
        return values;
    }

    private void preload() {
        dataAccess.scan(metadata, entity -> {
            id(entity);
            Object groupKey = groupKey(entity);
            Object mapKey = mapKey(entity);
            Map<Object, T> group = groups.get(groupKey, ignored -> new LinkedHashMap<>());
            if (group.putIfAbsent(mapKey, entity) != null) {
                throw new DataException("Duplicate map key during @Resident preload: type=" + metadata.type().getName()
                        + ", groupKey=" + groupKey + ", mapKey=" + mapKey);
            }
        });
    }

    private Map<Object, T> requireLoadedGroup(Object groupKey, String action) {
        Map<Object, T> group = groups.getIfPresent(groupKey);
        if (group == null) {
            throw new DataException("Group must be loaded before " + action + ": type="
                    + metadata.type().getName() + ", groupKey=" + groupKey
                    + ". Call getGroup(groupKey) first.");
        }
        return group;
    }

    private void requireCachedEntity(Map<Object, T> group, Object mapKey, Object id) {
        T cached = group.get(mapKey);
        if (cached == null || !Objects.equals(id(cached), id)) {
            throw new DataException("No matching entity in loaded group: type="
                    + metadata.type().getName() + ", mapKey=" + mapKey + ", id=" + id);
        }
    }

    private Object groupKey(T entity) {
        Object key = metadata.groupKey(entity);
        return Objects.requireNonNull(key, () -> "@GroupKey value must not be null: " + metadata.type().getName());
    }

    private Object mapKey(T entity) {
        Object key = metadata.mapKey(entity);
        return Objects.requireNonNull(key, () -> "Map key (@MapKey or @Id) must not be null: " + metadata.type().getName());
    }

    private void requireGroupKey(Object groupKey) {
        if (groupKey == null) throw new DataException("Group key must not be null: " + metadata.type().getName());
        // Also validates composite key arity and field types.
        metadata.groupKeyValues(groupKey);
    }

    private Object id(T entity) {
        Object id = metadata.idProperty().get(entity);
        if (id == null) throw new DataException("@Id value must not be null: " + metadata.type().getName());
        return id;
    }

}
