package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Cached entities keyed by {@code @Id}. Reads load a missing key synchronously on the calling thread (a Route
 * virtual thread only parks while storage answers). Writes go to memory at once and to storage on the next flush.
 *
 * <p>When the key is cached, writes are checked against it immediately: {@code insert} of an existing entity and
 * {@code update} of an absent one throw {@link DataOperationException}, and {@code delete} of an absent one does
 * nothing. Writes to keys that are not cached never read storage.
 */
public abstract class SingleRepository<K, E> {
    private EntityMeta meta;
    private EntityMapper mapper;
    private WriteBehindManager writer;
    private EntityCache<K, E> cache; // null values cache confirmed absence
    protected SingleRepository() {}
    final long cachedEntries() { return cache.size(); }
    final void initialize(EntityMeta meta, EntityMapper mapper, WriteBehindManager writer, Duration expiry, int maxEntries) {
        this.meta = meta; this.mapper = mapper; this.writer = writer;
        cache = new EntityCache<>(expiry.toNanos(), maxEntries);
        writer.addSweeper(idleDue -> cache.sweep(() -> id -> writer.pending(meta, id), idleDue));
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
    /** Cached entity, loading it on this thread when missing; null when storage has none. */
    public final E get(K key) { ready(); key(key); return cache.get(key, this::load); }

    /**
     * The entity, or a new one from {@code factory} inserted in the same step when storage has none. The factory
     * runs outside any lock and must return an entity whose {@code @Id} equals {@code key}; when another caller
     * inserts the same key first, that entity is returned and the factory's result is discarded.
     */
    @SuppressWarnings("unchecked")
    public final E getOrCreate(K key, Function<? super K, ? extends E> factory) {
        Objects.requireNonNull(factory);
        E existing = get(key);
        if (existing != null) return existing;
        E created = Objects.requireNonNull(factory.apply(key), "factory returned null");
        if (!key.equals(meta.getId(created))) throw new DataOperationException("Created entity has a different @Id: " + key);
        writer.admit(meta, key);
        Object[] result = {null};
        writer.mutate(() -> cache.update(key, (cached, current) -> {
            if (current != null) { result[0] = current; return current; }
            writer.record(meta, key, DataOperation.INSERT, created);
            result[0] = created;
            return created;
        }));
        return (E) result[0];
    }
    public final void insert(E entity) { change(entity, DataOperation.INSERT); }
    public final void update(E entity) { change(entity, DataOperation.UPDATE); }
    @SuppressWarnings("unchecked")
    private void change(E entity, DataOperation operation) {
        ready(); Objects.requireNonNull(entity); K id = (K) meta.getId(entity);
        writer.admit(meta, id);
        writer.mutate(() -> cache.update(id, (cached, current) -> {
            if (cached && operation == DataOperation.INSERT && current != null)
                throw new DataOperationException("Entity already exists: " + meta.entityType().getSimpleName() + "#" + id);
            if (cached && operation == DataOperation.UPDATE && current == null)
                throw new DataOperationException("Entity does not exist: " + meta.entityType().getSimpleName() + "#" + id);
            writer.record(meta, id, operation, entity);
            return entity;
        }));
    }
    public final void delete(K key) {
        ready(); key(key);
        writer.admit(meta, key);
        writer.mutate(() -> cache.update(key, (cached, current) -> {
            if (cached && current == null) return null; // already absent
            writer.record(meta, key, DataOperation.DELETE, null);
            return null;
        }));
    }
}
