package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.Objects;

public abstract class SingleRepository<K, E> {
    private record CacheEntity<E>(E entity) {}
    private EntityMeta meta;
    private EntityMapper mapper;
    private WriteBehindManager writer;
    private LoadingCache<K, CacheEntity<E>> cache;
    protected SingleRepository() {}
    final long cachedEntries() { return cache.estimatedSize(); }
    @SuppressWarnings("unchecked")
    final void initialize(EntityMeta meta, EntityMapper mapper, WriteBehindManager writer, Duration expiry) {
        this.meta = meta; this.mapper = mapper; this.writer = writer;
        cache = Caffeine.newBuilder().expireAfterAccess(expiry).build(key -> {
            // An evicted entity with unsaved changes reloads from memory, never from older storage.
            PendingBuffer.Change unsaved = writer.unsaved(meta, key);
            if (unsaved != null)
                return new CacheEntity<>(unsaved.operation() == DataOperation.DELETE ? null : (E) unsaved.entity());
            try { return new CacheEntity<>((E) this.mapper.load(meta, key)); }
            catch (Exception e) { throw new DataLoadException("Load failed: " + meta.entityType(), e); }
        });
    }
    private void ready() {
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        writer.ensureRunning();
    }
    private void key(Object key) {
        if (Objects.requireNonNull(key).getClass() != meta.keyType())
            throw new DataOperationException("Id type mismatch");
    }
    public final E get(K key) { ready(); key(key); return cache.get(key).entity(); }
    public final void insert(E entity) { change(entity, DataOperation.INSERT); }
    public final void update(E entity) { change(entity, DataOperation.UPDATE); }
    @SuppressWarnings("unchecked")
    private void change(E entity, DataOperation operation) {
        ready(); Objects.requireNonNull(entity); K id = (K) meta.getId(entity);
        writer.mutate(() -> {
            writer.record(meta, id, operation, entity);
            cache.put(id, new CacheEntity<>(entity));
        });
    }
    public final void delete(K key) {
        ready(); key(key);
        writer.mutate(() -> {
            writer.record(meta, key, DataOperation.DELETE, null);
            cache.put(key, new CacheEntity<>(null));
        });
    }
}
