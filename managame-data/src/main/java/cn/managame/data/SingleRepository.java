package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static cn.managame.data.DataOperation.DELETE;
import static cn.managame.data.DataOperation.INSERT;
import static cn.managame.data.DataOperation.UPDATE;

import cn.managame.data.internal.ChangeSet;
import cn.managame.data.internal.EntityKeys;
import cn.managame.data.internal.PendingChange;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Entities kept by their {@code @Id}, such as players. Extend it with the id and entity types and nothing else,
 * {@code class Players extends SingleRepository<Long, Player> {}}, and get the instance from
 * {@link GameData#repository}.
 *
 * <p>Reads come from memory; an entity not in memory is loaded on the asking thread, once however many threads ask for
 * it, and they block while the database answers (fine on a virtual thread, never on a Netty EventLoop). Changes go to
 * memory at once and to the database in the next round, which reads the entity then: after changing an entity, call
 * {@link #update(Object)} (or change it through {@link #update(Object, Consumer)}), and keep its collections
 * thread-safe. Every method may be called from any thread; changes to one entity are the application's to make one at
 * a time.
 *
 * <p>Memory knows which ids exist: {@code insert} of an id in memory throws, {@code update} of an id known not to
 * exist throws, and {@code delete} of one does nothing. Changes of ids not in memory never read the database. An
 * entity unused for the builder's {@code cacheExpire} leaves memory, as does one {@link #evict}ed; a later {@code get}
 * returns its unwritten state if it has one, the very same instance.
 *
 * <p>A repository registered with {@code preloadedRepositories} reads its whole table while GameData is built and
 * keeps every entity in memory for good: an id not in memory does not exist, so no read asks the database, and
 * {@link #all()} lists every entity.
 */
public abstract class SingleRepository<K, E> {
    /** Kept in memory, by a repository that loads on demand, in place of an entity the database does not have. */
    private static final Object NOT_FOUND = new Object();

    /** By id, the entity or NOT_FOUND. */
    private LoadingCache<Object, Object> entities;
    private EntityType entityType;
    private EntityKeys keys;
    private EntityMapper mapper;
    private ChangeSet changes;
    /** Whether the whole table is in memory, so an id not in memory does not exist. */
    private boolean preloaded;

    protected SingleRepository() {}

    /** A preloaded repository's cache never expires. */
    final void bind(ChangeSet changes, EntityKeys keys, Duration cacheExpire, boolean preloaded) {
        entityType = changes.type();
        this.keys = keys;
        mapper = changes.mapper();
        this.changes = changes;
        this.preloaded = preloaded;
        Caffeine<Object, Object> cache = preloaded ? Caffeine.newBuilder() : Caffeine.newBuilder().expireAfterAccess(cacheExpire);
        entities = cache.recordStats().build(this::load);
    }

    /** Reads the whole table into memory; GameData calls it while building a preloaded repository. */
    final void preload() {
        for (Object row : loadTable()) entities.asMap().put(keys.idOf(row), row);
    }

    /** The entity, from memory or loaded while this thread waits; null when the database has none. */
    public final E get(K id) {
        Object key = keys().checkKeyType(id);
        return entityOf(preloaded ? entities.getIfPresent(key) : entities.get(key));
    }

    /**
     * The entities of these ids that the database has, by id in the order given, for reading. Those not in memory are
     * read with one call of the mapper's {@code loadAll} on this thread; one another thread loads meanwhile keeps the
     * instance that thread stored first. Ids the database has not are left out, and remembered as {@code get}
     * remembers them.
     */
    @SuppressWarnings("unchecked")
    public final Map<K, E> getAll(Collection<? extends K> ids) {
        Map<Object, Object> found = new LinkedHashMap<>();   // in the order asked, null until loaded
        Set<Object> missing = new HashSet<>();
        for (K id : ids) {
            Object key = keys().checkKeyType(id);
            if (found.containsKey(key)) continue;
            Object entity = entities.getIfPresent(key);
            found.put(key, entity);
            if (entity == null) missing.add(key);
        }
        // Not Caffeine's getAll, which puts what it loads over what other threads stored meanwhile.
        if (!preloaded && !missing.isEmpty())
            loadAll(missing).forEach((id, loaded) -> {
                Object stored = entities.asMap().putIfAbsent(id, loaded);
                found.put(id, stored != null ? stored : loaded);
            });
        found.values().removeIf(entity -> entity == null || entity == NOT_FOUND);
        return (Map<K, E>) (Map<?, ?>) found;
    }

    /**
     * Every entity, live and for reading, of a repository registered with {@code preloadedRepositories};
     * IllegalStateException for one that loads on demand, whose memory holds only some.
     */
    @SuppressWarnings("unchecked")
    public final Collection<E> all() {
        keys();
        if (!preloaded) throw new IllegalStateException(getClass().getName() + " loads on demand, so memory holds only some of its entities");
        return (Collection<E>) (Collection<?>) entities.asMap().values();
    }

    /**
     * The entity, or one made by {@code factory} and inserted in the same step when the database has none. The factory
     * must return an entity with this {@code @Id}; when another thread inserts the id first, its entity is returned
     * instead.
     */
    public final E getOrCreate(K id, Function<? super K, ? extends E> factory) {
        Objects.requireNonNull(factory, "factory");
        E existing = get(id);
        if (existing != null) return existing;
        E created = Objects.requireNonNull(factory.apply(id), "factory returned null");
        if (!id.equals(keys.idOf(created)))
            throw new IllegalArgumentException("factory made " + entityName() + " " + keys.idOf(created) + " for " + id);
        return entityOf(entities.asMap().compute(id, (key, current) -> {
            if (current != null && current != NOT_FOUND) return current;
            changes.add(INSERT, key, null, null, created);
            return created;
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
        Object id = keys().idOf(entity);
        entities.asMap().compute(id, (key, current) -> {
            if (current != null && current != NOT_FOUND) throw new IllegalStateException(entityName() + " " + key + " already exists");
            changes.add(INSERT, key, null, null, entity);
            return entity;
        });
    }

    /**
     * Records that the entity changed, to be written with the state it has when the round writes it. The entity
     * becomes the one in memory for its id. IllegalStateException when the id is known not to exist.
     */
    public final void update(E entity) {
        Object id = keys().idOf(entity);
        if (entities.asMap().get(id) == entity) {   // the usual case: the entity in memory changed
            changes.add(UPDATE, id, null, null, entity);
            return;
        }
        entities.asMap().compute(id, (key, current) -> {
            if (current == NOT_FOUND || current == null && preloaded) throw new IllegalStateException(entityName() + " " + key + " does not exist");
            changes.add(UPDATE, key, null, null, entity);
            return entity;
        });
    }

    /** Deletes the entity with this id, whether or not it is in memory; nothing when it is known not to exist. */
    public final void delete(K id) {
        entities.asMap().compute(keys().checkKeyType(id), (key, current) -> {
            if (current == NOT_FOUND || current == null && preloaded) return current;
            changes.add(DELETE, key, null, null, current);
            return preloaded ? null : NOT_FOUND;
        });
    }

    /**
     * Drops the entity from memory; its changes not yet written are written all the same. IllegalStateException for a
     * preloaded repository, where an id not in memory does not exist.
     */
    public final void evict(K id) {
        Object key = keys().checkKeyType(id);
        if (preloaded) throw new IllegalStateException(getClass().getName() + " is preloaded and keeps every entity in memory");
        entities.invalidate(key);
    }

    /** Drops what expired or overflowed now rather than at the next use; GameData calls it every flush interval. */
    final void cleanUp() { entities.cleanUp(); }

    final EntityStats stats() {
        return new EntityStats(entityType.entityClass(), entities.estimatedSize(), changes.waiting(), changes.written(),
                changes.failures(), changes.dropped(), entities.stats());
    }

    /**
     * The entity's newest state: its change not yet written, or else what the database has. Runs inside the cache, so
     * a change of the id made by another thread meanwhile waits for the load and then applies to its result.
     */
    private Object load(Object id) {
        PendingChange change = changes.find(id);
        if (change != null) return change.operation() == DELETE ? NOT_FOUND : change.entity();
        Object loaded;
        try {
            loaded = mapper.load(entityType, id);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load " + entityName() + " " + id, e);
        }
        return loaded == null ? NOT_FOUND : loaded;
    }

    /** Each id's newest state as {@link #load} gives it, those without a change not yet written read in one call. */
    private Map<Object, Object> loadAll(Set<Object> ids) {
        Map<Object, Object> loaded = new HashMap<>();
        List<Object> unchanged = new ArrayList<>(ids.size());
        for (Object id : ids) {
            PendingChange change = changes.find(id);
            if (change == null) unchanged.add(id);
            else loaded.put(id, change.operation() == DELETE ? NOT_FOUND : change.entity());
        }
        if (unchanged.isEmpty()) return loaded;
        List<Object> rows;
        try {
            rows = mapper.loadAll(entityType, unchanged);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load " + unchanged.size() + " of " + entityName(), e);
        }
        for (Object row : rows) loaded.putIfAbsent(keys.idOf(row), row);
        for (Object id : unchanged) loaded.putIfAbsent(id, NOT_FOUND);
        return loaded;
    }

    private List<Object> loadTable() {
        try {
            return mapper.loadTable(entityType);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load the table of " + entityName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private E entityOf(Object entity) { return entity == NOT_FOUND ? null : (E) entity; }

    private String entityName() { return entityType.entityClass().getSimpleName(); }

    private EntityKeys keys() {
        if (keys == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        return keys;
    }
}
