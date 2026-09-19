package cn.managame.core.repository;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.cache.EntityCache;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.MetadataRegistry;
import cn.managame.core.write.WriteBehindEngine;
import cn.managame.core.write.WriteOperation;

import java.time.Duration;
import java.util.Optional;

public final class DefaultSingleRepository<T, ID> implements SingleRepository<T, ID> {
    private final EntityMetadata<T> metadata;
    private final DataAccess dataAccess;
    private final WriteBehindEngine writer;
    private final EntityCache<ID, Optional<T>> cache;

    public DefaultSingleRepository(
            Class<T> entityType,
            MetadataRegistry registry,
            DataAccess dataAccess,
            WriteBehindEngine writer,
            Duration cacheExpireAfterAccess,
            boolean ensureSchema) {
        this.metadata = registry.get(entityType);
        dataAccess.validateMapping(metadata);
        this.dataAccess = dataAccess;
        this.writer = writer;
        this.cache = new EntityCache<>(cacheExpireAfterAccess, metadata.resident());
        if (ensureSchema) dataAccess.ensureSchema(metadata);
        if (metadata.resident()) preload();
    }

    @Override
    public Optional<T> get(ID id) {
        requireId(id, "get");
        if (metadata.resident()) {
            Optional<T> value = cache.getIfPresent(id);
            return value == null ? Optional.empty() : value;
        }
        return cache.get(id, key -> dataAccess.findById(metadata, key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void insert(T entity) {
        ID id = (ID) metadata.idProperty().get(entity);
        requireId(id, "insert");
        writer.submit(WriteOperation.insert(metadata, entity, id, null));
        cache.put(id, Optional.of(entity));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void update(T entity) {
        ID id = (ID) metadata.idProperty().get(entity);
        requireId(id, "update");
        writer.submit(WriteOperation.update(metadata, entity, id, null));
        cache.put(id, Optional.of(entity));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void delete(T entity) {
        ID id = (ID) metadata.idProperty().get(entity);
        requireId(id, "delete");
        writer.submit(WriteOperation.delete(metadata, id, null));
        cache.put(id, Optional.empty());
    }

    @Override
    public void deleteById(ID id) {
        requireId(id, "delete");
        writer.submit(WriteOperation.delete(metadata, id, null));
        cache.put(id, Optional.empty());
    }

    @Override
    public boolean resident() { return metadata.resident(); }

    @SuppressWarnings("unchecked")
    private void preload() {
        dataAccess.scan(metadata, entity -> {
            ID id = (ID) metadata.idProperty().get(entity);
            requireId(id, "resident preload");
            cache.put(id, Optional.of(entity));
        });
    }

    private void requireId(Object id, String action) {
        if (id == null) throw new DataException("@Id value must not be null on " + action + ": " + metadata.type().getName());
    }
}
