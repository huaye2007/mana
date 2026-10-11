package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.Objects;

public abstract class SingleRepository<K, E> {
    private EntityMeta meta;
    private EntityMapper mapper;
    private WriteBehindManager writer;
    private EntityCache<K, E> cache;   // null values cache confirmed absence
    protected SingleRepository() {}
    final long cachedEntries() { return cache.size(); }
    final void initialize(EntityMeta meta, EntityMapper mapper, WriteBehindManager writer, Duration expiry) {
        this.meta = meta; this.mapper = mapper; this.writer = writer;
        cache = new EntityCache<>(expiry.toNanos());
        writer.addSweeper(() -> cache.sweep(() -> id -> writer.pending(meta, id)));
    }
    @SuppressWarnings("unchecked")
    private E load(K key) {
        // Only clean keys are ever missing from the cache, so storage is current for them.
        try { return (E) mapper.load(meta, key); }
        catch (Exception e) { throw new DataLoadException("Load failed: " + meta.entityType(), e); }
    }
    private void ready() {
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        writer.ensureRunning();
    }
    private void key(Object key) {
        if (Objects.requireNonNull(key).getClass() != meta.keyType())
            throw new DataOperationException("Id type mismatch");
    }
    public final E get(K key) { ready(); key(key); return cache.get(key, this::load); }
    public final void insert(E entity) { change(entity, DataOperation.INSERT); }
    public final void update(E entity) { change(entity, DataOperation.UPDATE); }
    @SuppressWarnings("unchecked")
    private void change(E entity, DataOperation operation) {
        ready(); Objects.requireNonNull(entity); K id = (K) meta.getId(entity);
        writer.mutate(() -> cache.update(id, current -> { writer.record(meta, id, operation, entity); return entity; }));
    }
    public final void delete(K key) {
        ready(); key(key);
        writer.mutate(() -> cache.update(key, current -> { writer.record(meta, key, DataOperation.DELETE, null); return null; }));
    }
}
