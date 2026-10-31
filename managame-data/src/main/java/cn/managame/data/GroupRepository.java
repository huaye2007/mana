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
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Entities kept in groups, such as the items of each player: a group is every entity with the same {@code @GroupKey}
 * values, loaded with one query and held as a map by {@code @MapKey}, unique within the group. Extend it with the map
 * key and entity types and nothing else, {@code class Items extends GroupRepository<Integer, Item> {}}, and get the
 * instance from {@link GameData#repository}. {@code K} is the {@code @MapKey} field's type, or String for several
 * MapKey fields, joined by {@link MapKeys#of}.
 *
 * <p>A group is loaded whole on first use, by reads and changes alike, once however many threads ask for it, and they
 * block while the database answers. Changes go to memory at once, checked against the group, and to the database in the
 * next round, which reads the entities then: after changing an entity call {@link #update(Object)}, and keep its
 * collections thread-safe. A group unused for the builder's {@code cacheExpire} leaves memory, as do the least used
 * beyond its {@code maxCacheEntries} and one {@link #evict}ed; loaded again, it has its unwritten entities, the very
 * same instances.
 */
public abstract class GroupRepository<K, E> {
    private record Group<K, E>(ConcurrentHashMap<K, E> entities, Map<K, E> view) {
        Group() { this(new ConcurrentHashMap<>()); }

        private Group(ConcurrentHashMap<K, E> entities) { this(entities, Collections.unmodifiableMap(entities)); }
    }

    private LoadingCache<GroupKeys, Group<K, E>> groups;
    private EntityType entityType;
    private EntityKeys keys;
    private EntityMapper mapper;
    private ChangeSet changes;

    protected GroupRepository() {}

    /**
     * The cache tidies itself on the threads that use it, which costs less than waking another thread, and times
     * accesses by {@code ticker}. Loads run on {@code loads}, one per group however many threads ask for it, outside
     * the cache's locks; the asking threads wait for it.
     */
    final void bind(ChangeSet changes, EntityKeys keys, Duration cacheExpire, long maxCacheEntries, Ticker ticker,
            Executor loads) {
        entityType = changes.type();
        this.keys = keys;
        mapper = changes.mapper();
        this.changes = changes;
        groups = Caffeine.newBuilder().executor(Runnable::run).ticker(ticker).expireAfterAccess(cacheExpire)
                .maximumSize(maxCacheEntries)
                .buildAsync((GroupKeys groupKey, Executor executor) ->
                        CompletableFuture.supplyAsync(() -> load(groupKey), loads))
                .synchronous();
    }

    /** A live, read-only view of the group, loaded first when not in memory; empty when the database has none. */
    public final Map<K, E> getGroup(GroupKeys groupKey) { return group(keys().checkGroupKey(groupKey)).view(); }

    /** The entity at {@code mapKey} in the group, or null. */
    public final E get(GroupKeys groupKey, K mapKey) {
        keys().checkKeyType(mapKey);
        return group(keys.checkGroupKey(groupKey)).entities().get(mapKey);
    }

    /**
     * Changes the entity at {@code mapKey} and records the update in one call; false, changing nothing, when the group
     * has no such entity. {@code change} runs on this thread and must not change the entity's keys.
     */
    public final boolean update(GroupKeys groupKey, K mapKey, Consumer<? super E> change) {
        Objects.requireNonNull(change, "change");
        E entity = get(groupKey, mapKey);
        if (entity == null) return false;
        change.accept(entity);
        update(entity);
        return true;
    }

    /** Adds an entity to its group; IllegalStateException when the group has one at its map key. */
    public final void insert(E entity) {
        Object id = keys().idOf(entity);
        GroupKeys groupKey = keys.groupKeyOf(entity);
        group(groupKey).entities().compute(mapKeyOf(entity), (key, existing) -> {
            if (existing != null) throw new IllegalStateException(entityName() + " " + key + " already exists in group " + groupKey);
            changes.add(INSERT, id, groupKey, key, entity);
            return entity;
        });
    }

    /**
     * Records that the entity changed, to be written with the state it has when the round writes it.
     * IllegalStateException when its group has no entity at its map key, or another one.
     */
    public final void update(E entity) {
        Object id = keys().idOf(entity);
        GroupKeys groupKey = keys.groupKeyOf(entity);
        K mapKey = mapKeyOf(entity);
        Group<K, E> group = group(groupKey);
        if (group.entities().get(mapKey) == entity) {   // the usual case: the entity in memory changed
            changes.add(UPDATE, id, groupKey, mapKey, entity);
            return;
        }
        group.entities().compute(mapKey, (key, existing) -> {
            if (existing == null) throw new IllegalStateException(entityName() + " " + key + " does not exist in group " + groupKey);
            if (!id.equals(keys.idOf(existing)))
                throw new IllegalStateException(entityName() + " " + key + " of group " + groupKey + " is " + keys.idOf(existing) + ", not " + id);
            changes.add(UPDATE, id, groupKey, key, entity);
            return entity;
        });
    }

    /** Deletes the entity at the entity's map key from its group; nothing when there is none. */
    public final void delete(E entity) {
        GroupKeys groupKey = keys().groupKeyOf(entity);
        delete(group(groupKey), groupKey, mapKeyOf(entity));
    }

    /** Deletes every entity of the group. */
    public final void deleteGroup(GroupKeys groupKey) {
        Group<K, E> group = group(keys().checkGroupKey(groupKey));
        for (K mapKey : new ArrayList<>(group.entities().keySet())) delete(group, groupKey, mapKey);
    }

    /** Drops the group from memory; its changes not yet written are written all the same. */
    public final void evict(GroupKeys groupKey) { groups.invalidate(keys().checkGroupKey(groupKey)); }

    /** Drops what expired or overflowed now rather than at the next use; GameData calls it every flush interval. */
    final void cleanUp() { groups.cleanUp(); }

    private void delete(Group<K, E> group, GroupKeys groupKey, K mapKey) {
        group.entities().computeIfPresent(mapKey, (key, existing) -> {
            changes.add(DELETE, keys.idOf(existing), groupKey, key, existing);
            return null;
        });
    }

    /**
     * The group, from memory or loaded while this thread waits. Changes are made only to a group in memory, after
     * loading it, so a group evicted while one is made gets the change from the change set when it is loaded again.
     */
    private Group<K, E> group(GroupKeys groupKey) {
        Group<K, E> group = groups.getIfPresent(groupKey);   // a hit allocates nothing, unlike get
        return group != null ? group : groups.get(groupKey);
    }

    /**
     * What the database has for the group with its unwritten changes laid over, deletes first, so a row deleted and
     * another inserted at the same map key end as the inserted one.
     */
    @SuppressWarnings("unchecked")
    private Group<K, E> load(GroupKeys groupKey) {
        List<PendingChange> unwritten = changes.findGroup(groupKey);
        List<Object> loaded;
        try {
            loaded = mapper.loadGroup(entityType, groupKey);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load " + entityName() + " group " + groupKey, e);
        }
        Group<K, E> group = new Group<>();
        for (Object entity : loaded) {
            K mapKey = mapKeyOf((E) entity);
            if (group.entities().putIfAbsent(mapKey, (E) entity) != null)
                throw new DataException(DATA_LOAD_FAILED, "group " + groupKey + " of " + entityName() + " has two rows with map key " + mapKey, null);
        }
        for (PendingChange change : unwritten) if (change.operation() == DELETE) group.entities().remove((K) change.mapKey());
        for (PendingChange change : unwritten)
            if (change.operation() != DELETE) group.entities().put((K) change.mapKey(), (E) change.entity());
        return group;
    }

    @SuppressWarnings("unchecked")
    private K mapKeyOf(E entity) { return (K) keys.mapKeyOf(entity); }

    private String entityName() { return entityType.entityClass().getSimpleName(); }

    private EntityKeys keys() {
        if (keys == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        return keys;
    }
}
