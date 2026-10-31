package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static cn.managame.data.DataOperation.DELETE;
import static cn.managame.data.DataOperation.INSERT;
import static cn.managame.data.DataOperation.UPDATE;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Entities kept by their {@code @Id}, such as players. Extend it with the id and entity types and nothing else,
 * {@code class Players extends SingleRepository<Long, Player> {}}, and get the instance from
 * {@link GameData#repository}.
 *
 * <p>Reads come from memory; an entity not in memory is loaded on the calling thread, which blocks while the database
 * answers (fine on a virtual thread, never on a Netty EventLoop). Changes go to memory at once and to the database in
 * the next round, which reads the entity then: after changing an entity, call {@link #update(Object)} (or change it
 * through {@link #update(Object, Consumer)}), and keep its collections thread-safe. Every method may be called from
 * any thread; changes to one entity are the application's to make one at a time.
 *
 * <p>Memory knows which ids exist: {@code insert} of an id in memory throws, {@code update} of an id known not to
 * exist throws, and {@code delete} of one does nothing. Changes of ids not in memory never read the database. An
 * entity unused for the builder's {@code cacheExpire} leaves memory, as does one {@link #evict}ed; a later
 * {@code get} returns its unwritten state if it has one, the very same instance.
 */
public abstract class SingleRepository<K, E> {
    /** Kept in memory in place of an entity the database does not have. */
    private static final Object NOT_FOUND = new Object();

    private static final class Entry {
        final Object entity;
        volatile long lastAccess;

        Entry(Object entity, long accessTime) {
            this.entity = entity;
            lastAccess = accessTime;
        }
    }

    private final ConcurrentHashMap<Object, Entry> cache = new ConcurrentHashMap<>();
    private EntityType entityType;
    private EntityMapper mapper;
    private ChangeSet changes;
    /** What an access marks an entry with; GameData sets it every flush interval. */
    private volatile long accessTime = System.nanoTime();

    protected SingleRepository() {}

    final void bind(ChangeSet changes) {
        entityType = changes.type;
        mapper = changes.mapper;
        this.changes = changes;
    }

    /** The entity, from memory or loaded on this thread; null when the database has none. */
    public final E get(K id) {
        Object key = entityType().checkKeyType(id);
        Entry entry = cache.get(key);
        if (entry != null) {
            markAccessed(entry);
            return entityOf(entry);
        }
        return entityOf(load(key));
    }

    /**
     * The entity, or one made by {@code factory} and inserted in the same step when the database has none. The
     * factory must return an entity with this {@code @Id}; when another thread inserts the id first, its entity is
     * returned instead.
     */
    public final E getOrCreate(K id, Function<? super K, ? extends E> factory) {
        Objects.requireNonNull(factory, "factory");
        E existing = get(id);
        if (existing != null) return existing;
        E created = Objects.requireNonNull(factory.apply(id), "factory returned null");
        if (!id.equals(entityType.idOf(created)))
            throw new IllegalArgumentException("factory made " + entityType.entityClass().getSimpleName() + " " + entityType.idOf(created) + " for " + id);
        return entityOf(cache.compute(id, (key, entry) -> {
            if (entry != null && entry.entity != NOT_FOUND) return entry;
            changes.add(INSERT, key, null, null, created);
            return new Entry(created, accessTime);
        }));
    }

    /**
     * Changes the entity and records the update in one call, so the two cannot come apart; false, changing nothing,
     * when the database has no such entity. {@code change} runs on this thread.
     */
    public final boolean update(K id, Consumer<? super E> change) {
        Objects.requireNonNull(change, "change");
        E entity = get(id);
        if (entity == null) return false;
        change.accept(entity);
        update(entity);
        return true;
    }

    /** Adds a new entity; IllegalStateException when one with its id is in memory. */
    public final void insert(E entity) {
        Object id = entityType().idOf(entity);
        cache.compute(id, (key, entry) -> {
            if (entry != null && entry.entity != NOT_FOUND)
                throw new IllegalStateException(entityType.entityClass().getSimpleName() + " " + key + " already exists");
            changes.add(INSERT, key, null, null, entity);
            return new Entry(entity, accessTime);
        });
    }

    /**
     * Records that the entity changed, to be written with the state it has when the round writes it. The entity
     * becomes the one in memory for its id. IllegalStateException when the id is known not to exist.
     */
    public final void update(E entity) {
        Object id = entityType().idOf(entity);
        Entry cached = cache.get(id);
        if (cached != null && cached.entity == entity) {   // the usual case: the entity in memory changed
            changes.add(UPDATE, id, null, null, entity);
            markAccessed(cached);
            return;
        }
        cache.compute(id, (key, entry) -> {
            if (entry != null && entry.entity == NOT_FOUND)
                throw new IllegalStateException(entityType.entityClass().getSimpleName() + " " + key + " does not exist");
            changes.add(UPDATE, key, null, null, entity);
            return new Entry(entity, accessTime);
        });
    }

    /** Deletes the entity with this id, whether or not it is in memory; nothing when it is known not to exist. */
    public final void delete(K id) {
        cache.compute(entityType().checkKeyType(id), (key, entry) -> {
            if (entry != null && entry.entity == NOT_FOUND) return entry;
            changes.add(DELETE, key, null, null, entry == null ? null : entry.entity);
            return new Entry(NOT_FOUND, accessTime);
        });
    }

    /** Drops the entity from memory; its changes not yet written are written all the same. */
    public final void evict(K id) { cache.remove(entityType().checkKeyType(id)); }

    final void setAccessTime(long time) { accessTime = time; }

    /** Drops entries last accessed before {@code time}; runs on GameData's scheduler thread. */
    final void evictUnusedSince(long time) {
        for (var cached : cache.entrySet())
            if (cached.getValue().lastAccess - time < 0)
                cache.computeIfPresent(cached.getKey(), (key, entry) -> entry.lastAccess - time < 0 ? null : entry);
    }

    /**
     * Loads outside any lock. A change of the id made meanwhile left its own entry, which wins: every change leaves
     * one (the entity, or NOT_FOUND for a delete), and a load only fills an empty slot.
     */
    private Entry load(Object id) {
        Change change = changes.find(id);
        Object entity;
        if (change != null) {
            entity = change.operation == DELETE ? NOT_FOUND : change.entity;
        } else {
            Object loaded;
            try {
                loaded = mapper.load(entityType, id);
            } catch (Exception e) {
                throw new DataException(DATA_LOAD_FAILED, "cannot load " + entityType.entityClass().getSimpleName() + " " + id, e);
            }
            entity = loaded == null ? NOT_FOUND : loaded;
        }
        Entry created = new Entry(entity, accessTime);
        Entry existing = cache.putIfAbsent(id, created);
        return existing != null ? existing : created;
    }

    private void markAccessed(Entry entry) {
        long time = accessTime;
        if (entry.lastAccess != time) entry.lastAccess = time;
    }

    @SuppressWarnings("unchecked")
    private E entityOf(Entry entry) { return entry.entity == NOT_FOUND ? null : (E) entry.entity; }

    private EntityType entityType() {
        if (entityType == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        return entityType;
    }
}
