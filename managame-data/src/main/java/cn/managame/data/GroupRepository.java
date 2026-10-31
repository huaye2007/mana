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
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 *
 * <p>A repository registered with {@code preloadedRepositories} reads its whole table while GameData is built and
 * keeps every group in memory for good: a group not in memory has no entities, so no read asks the database, and
 * {@link #all()} lists every group.
 */
public abstract class GroupRepository<K, E> {
    private record Group<K, E>(ConcurrentHashMap<K, E> entities, Map<K, E> view) {
        Group() { this(new ConcurrentHashMap<>()); }

        private Group(ConcurrentHashMap<K, E> entities) { this(entities, Collections.unmodifiableMap(entities)); }
    }

    private LoadingCache<GroupKeys, Group<K, E>> groups;
    /** The cache's counts; hits found by asMap().get are recorded here, as the cache records everything else. */
    private final StatsCounter cacheStats = new ConcurrentStatsCounter();
    private EntityType entityType;
    private EntityKeys keys;
    private EntityMapper mapper;
    private ChangeSet changes;
    /** Whether the whole table is in memory, so a group not in memory has no entities. */
    private boolean preloaded;

    protected GroupRepository() {}

    /**
     * {@code cache} carries the expiry and size bound, none for a preloaded repository. Loads run on {@code loads}, one
     * per group however many threads ask for it, outside the cache's locks; the asking threads wait for it.
     */
    final void bind(ChangeSet changes, EntityKeys keys, Caffeine<Object, Object> cache, Executor loads, boolean preloaded) {
        entityType = changes.type();
        this.keys = keys;
        mapper = changes.mapper();
        this.changes = changes;
        this.preloaded = preloaded;
        groups = cache.recordStats(() -> cacheStats)
                .buildAsync((GroupKeys groupKey, Executor executor) ->
                        CompletableFuture.supplyAsync(() -> load(groupKey), loads))
                .synchronous();
    }

    /** Reads the whole table into memory; GameData calls it while building a preloaded repository. */
    final void preload() {
        rowsByGroup(loadTable()).forEach((groupKey, rows) -> groups.asMap().put(groupKey, groupOf(groupKey, rows, List.of())));
    }

    /** A live, read-only view of the group, loaded first when not in memory; empty when the database has none. */
    public final Map<K, E> getGroup(GroupKeys groupKey) {
        Group<K, E> group = existingGroup(keys().checkGroupKey(groupKey));
        return group == null ? Map.of() : group.view();
    }

    /** The entity at {@code mapKey} in the group, or null. */
    public final E get(GroupKeys groupKey, K mapKey) {
        keys().checkKeyType(mapKey);
        Group<K, E> group = existingGroup(keys.checkGroupKey(groupKey));
        return group == null ? null : group.entities().get(mapKey);
    }

    /**
     * Every group with entities, each a live read-only view, of a repository registered with
     * {@code preloadedRepositories}; which groups there are is read when called. IllegalStateException for one that
     * loads on demand, whose memory holds only some.
     */
    public final Map<GroupKeys, Map<K, E>> all() {
        keys();
        if (!preloaded) throw new IllegalStateException(getClass().getName() + " loads on demand, so memory holds only some of its groups");
        Map<GroupKeys, Map<K, E>> all = new HashMap<>();
        groups.asMap().forEach((groupKey, group) -> { if (!group.entities().isEmpty()) all.put(groupKey, group.view()); });
        return Collections.unmodifiableMap(all);
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
        Group<K, E> group = preloaded ? groups.asMap().computeIfAbsent(groupKey, key -> new Group<>()) : group(groupKey);
        group.entities().compute(mapKeyOf(entity), (key, existing) -> {
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
        Group<K, E> group = existingGroup(groupKey);
        if (group == null) throw new IllegalStateException(entityName() + " " + mapKey + " does not exist in group " + groupKey);
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
        Group<K, E> group = existingGroup(groupKey);
        if (group != null) delete(group, groupKey, mapKeyOf(entity));
    }

    /** Deletes every entity of the group. */
    public final void deleteGroup(GroupKeys groupKey) {
        Group<K, E> group = existingGroup(keys().checkGroupKey(groupKey));
        if (group == null) return;
        for (K mapKey : new ArrayList<>(group.entities().keySet())) delete(group, groupKey, mapKey);
    }

    /**
     * Drops the group from memory; its changes not yet written are written all the same. IllegalStateException for a
     * preloaded repository, where a group not in memory has no entities.
     */
    public final void evict(GroupKeys groupKey) {
        GroupKeys checked = keys().checkGroupKey(groupKey);
        if (preloaded) throw new IllegalStateException(getClass().getName() + " is preloaded and keeps every group in memory");
        groups.invalidate(checked);
    }

    /** Drops what expired or overflowed now rather than at the next use; GameData calls it every flush interval. */
    final void cleanUp() { groups.cleanUp(); }

    final EntityStats stats() {
        return new EntityStats(entityType.entityClass(), groups.estimatedSize(), changes.waiting(), changes.written(),
                changes.failures(), changes.dropped(), cacheStats.snapshot());
    }

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
        Group<K, E> group = groups.asMap().get(groupKey);   // a hit allocates nothing, unlike get, and waits for no load
        if (group == null) return groups.get(groupKey);
        cacheStats.recordHits(1);
        return group;
    }

    /** As {@link #group}, but null for a group a preloaded repository does not have, which is kept nowhere. */
    private Group<K, E> existingGroup(GroupKeys groupKey) {
        if (!preloaded) return group(groupKey);
        Group<K, E> group = groups.asMap().get(groupKey);
        if (group == null) cacheStats.recordMisses(1);
        else cacheStats.recordHits(1);
        return group;
    }

    /** What the database has for the group with its unwritten changes laid over. */
    private Group<K, E> load(GroupKeys groupKey) {
        List<PendingChange> unwritten = changes.findGroup(groupKey);
        return groupOf(groupKey, loadGroup(groupKey), unwritten);
    }

    /** A group of these rows with the changes laid over, deletes first, so a row deleted and another inserted at the same map key end as the inserted one. */
    @SuppressWarnings("unchecked")
    private Group<K, E> groupOf(GroupKeys groupKey, List<Object> rows, List<PendingChange> unwritten) {
        Group<K, E> group = new Group<>();
        for (Object entity : rows) {
            K mapKey = mapKeyOf((E) entity);
            if (group.entities().putIfAbsent(mapKey, (E) entity) != null)
                throw new DataException(DATA_LOAD_FAILED, "group " + groupKey + " of " + entityName() + " has two rows with map key " + mapKey, null);
        }
        for (PendingChange change : unwritten) if (change.operation() == DELETE) group.entities().remove((K) change.mapKey());
        for (PendingChange change : unwritten)
            if (change.operation() != DELETE) group.entities().put((K) change.mapKey(), (E) change.entity());
        return group;
    }

    private Map<GroupKeys, List<Object>> rowsByGroup(List<Object> rows) {
        Map<GroupKeys, List<Object>> byGroup = new HashMap<>();
        for (Object row : rows) byGroup.computeIfAbsent(keys.groupKeyOf(row), key -> new ArrayList<>()).add(row);
        return byGroup;
    }

    private List<Object> loadGroup(GroupKeys groupKey) {
        try {
            return mapper.loadGroup(entityType, groupKey);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load " + entityName() + " group " + groupKey, e);
        }
    }

    private List<Object> loadTable() {
        try {
            return mapper.loadTable(entityType);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load the table of " + entityName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private K mapKeyOf(E entity) { return (K) keys.mapKeyOf(entity); }

    private String entityName() { return entityType.entityClass().getSimpleName(); }

    private EntityKeys keys() {
        if (keys == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        return keys;
    }
}
