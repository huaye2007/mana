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
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Entities kept by their {@code @Id}, such as players. Extend it with the id and entity types and nothing else,
 * {@code class Players extends SingleRepository<Long, Player> {}}, and get the instance from
 * {@link GameData#repository}.
 *
 * <p>Reads come from memory; an entity not in memory is loaded once however many threads ask for it, and they block
 * while the database answers (fine on a virtual thread, never on a Netty EventLoop). Changes go to memory at once and
 * to the database in the next round, which reads the entity then: after changing an entity, call
 * {@link #update(Object)} (or change it through {@link #update(Object, Consumer)}), and keep its collections
 * thread-safe. Every method may be called from any thread; changes to one entity are the application's to
 * make one at a time.
 *
 * <p>Memory knows which ids exist: {@code insert} of an id in memory throws, {@code update} of an id known not to
 * exist throws, and {@code delete} of one does nothing. Changes of ids not in memory never read the database. An
 * entity unused for the builder's {@code cacheExpire} leaves memory, as do the least used beyond its
 * {@code maxCacheEntries} and one {@link #evict}ed; a later {@code get} returns its unwritten state if it has one, the
 * very same instance.
 */
public abstract class SingleRepository<K, E> {
    /** Kept in memory in place of an entity the database does not have. */
    private static final Object NOT_FOUND = new Object();

    /** By id, the entity or NOT_FOUND. */
    private LoadingCache<Object, Object> entities;
    /** The cache's counts; hits found by asMap().get are recorded here, as the cache records everything else. */
    private final StatsCounter cacheStats = new ConcurrentStatsCounter();
    private EntityType entityType;
    private EntityKeys keys;
    private EntityMapper mapper;
    private ChangeSet changes;

    protected SingleRepository() {}

    /**
     * The cache tidies itself on the threads that use it, which costs less than waking another thread, and times
     * accesses by {@code ticker}. Loads run on {@code loads}, one per id however many threads ask for it and one call
     * of the mapper's loadAll per getAll, outside the cache's locks; the asking threads wait for them.
     */
    final void bind(ChangeSet changes, EntityKeys keys, Duration cacheExpire, long maxCacheEntries, Ticker ticker,
            Executor loads) {
        entityType = changes.type();
        this.keys = keys;
        mapper = changes.mapper();
        this.changes = changes;
        entities = Caffeine.newBuilder().executor(Runnable::run).ticker(ticker).expireAfterAccess(cacheExpire)
                .maximumSize(maxCacheEntries).recordStats(() -> cacheStats)
                .buildAsync(new AsyncCacheLoader<Object, Object>() {
                    @Override public CompletableFuture<Object> asyncLoad(Object id, Executor executor) {
                        return CompletableFuture.supplyAsync(() -> load(id), loads);
                    }

                    @Override public CompletableFuture<Map<Object, Object>> asyncLoadAll(Set<?> ids, Executor executor) {
                        return CompletableFuture.supplyAsync(() -> loadAll(ids), loads);
                    }
                })
                .synchronous();
    }

    /** The entity, from memory or loaded while this thread waits; null when the database has none. */
    public final E get(K id) {
        Object key = keys().checkKeyType(id);
        Object entity = entities.asMap().get(key);   // a hit allocates nothing, unlike get, and waits for no load
        if (entity == null) return entityOf(entities.get(key));
        cacheStats.recordHits(1);
        return entityOf(entity);
    }

    /**
     * The entities of these ids that the database has, by id in the order given. Those not in memory are loaded with
     * one call of the mapper's {@code loadAll} while this thread waits, each once however many threads ask for it: a
     * {@code get} of one of them meanwhile waits for that call. Ids the database has not are left out, and remembered as
     * {@code get} remembers them.
     */
    @SuppressWarnings("unchecked")
    public final Map<K, E> getAll(Collection<? extends K> ids) {
        // Those in memory are found as get finds them; Caffeine's getAll, which allocates for every key, gets the rest.
        Map<Object, Object> found = new LinkedHashMap<>();   // in the order asked, null until loaded
        List<Object> missing = new ArrayList<>(0);
        for (K id : ids) {
            Object key = keys().checkKeyType(id);
            if (found.containsKey(key)) continue;
            Object entity = entities.asMap().get(key);
            found.put(key, entity);
            if (entity == null) missing.add(key);
        }
        cacheStats.recordHits(found.size() - missing.size());
        if (!missing.isEmpty()) found.putAll(entities.getAll(missing));
        found.values().removeIf(entity -> entity == NOT_FOUND);
        return Collections.unmodifiableMap((Map<K, E>) (Map<?, ?>) found);
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
            if (current == NOT_FOUND) throw new IllegalStateException(entityName() + " " + key + " does not exist");
            changes.add(UPDATE, key, null, null, entity);
            return entity;
        });
    }

    /** Deletes the entity with this id, whether or not it is in memory; nothing when it is known not to exist. */
    public final void delete(K id) {
        entities.asMap().compute(keys().checkKeyType(id), (key, current) -> {
            if (current == NOT_FOUND) return current;
            changes.add(DELETE, key, null, null, current);
            return NOT_FOUND;
        });
    }

    /** Drops the entity from memory; its changes not yet written are written all the same. */
    public final void evict(K id) { entities.invalidate(keys().checkKeyType(id)); }

    /** Drops what expired or overflowed now rather than at the next use; GameData calls it every flush interval. */
    final void cleanUp() { entities.cleanUp(); }

    final EntityStats stats() {
        return new EntityStats(entityType.entityClass(), entities.estimatedSize(), changes.waiting(), changes.written(),
                changes.failures(), changes.dropped(), cacheStats.snapshot());
    }

    /**
     * The entity's newest state: its change not yet written, or else what the database has. A change of the id made by
     * another thread while this loads waits for the load and then applies to its result.
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
    private Map<Object, Object> loadAll(Set<?> ids) {
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

    @SuppressWarnings("unchecked")
    private E entityOf(Object entity) { return entity == NOT_FOUND ? null : (E) entity; }

    private String entityName() { return entityType.entityClass().getSimpleName(); }

    private EntityKeys keys() {
        if (keys == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        return keys;
    }
}
