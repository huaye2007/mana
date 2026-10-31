package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOAD_FAILED;
import static cn.managame.data.DataOperation.DELETE;
import static cn.managame.data.DataOperation.INSERT;
import static cn.managame.data.DataOperation.UPDATE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Entities kept in groups, such as the items of each player: a group is every entity with the same {@code @GroupKey}
 * values, loaded with one query and held as a map by {@code @MapKey}, unique within the group. Extend it with the map
 * key and entity types and nothing else, {@code class Items extends GroupRepository<Integer, Item> {}}, and get the
 * instance from {@link GameData#repository}. {@code K} is the {@code @MapKey} field's type, or String for several
 * MapKey fields, joined by {@link MapKeys#of}.
 *
 * <p>A group is loaded whole on first use, by reads and changes alike, on the calling thread. Changes go to memory at
 * once, checked against the group, and to the database in the next round, which reads the entities then: after
 * changing an entity call {@link #update(Object)}, and keep its collections thread-safe. A group unused for the
 * builder's {@code cacheExpire} leaves memory, as does one {@link #evict}ed; loaded again, it has its unwritten
 * entities, the very same instances.
 */
public abstract class GroupRepository<K, E> {
    private static final class Group<K, E> {
        final ConcurrentHashMap<K, E> entities = new ConcurrentHashMap<>();
        final Map<K, E> view = Collections.unmodifiableMap(entities);
        volatile long lastAccess;

        Group(long accessTime) { lastAccess = accessTime; }
    }

    private final ConcurrentHashMap<GroupKeys, Group<K, E>> cache = new ConcurrentHashMap<>();
    private EntityType entityType;
    private EntityMapper mapper;
    private ChangeSet changes;
    /** What an access marks a group with; GameData sets it every flush interval. */
    private volatile long accessTime = System.nanoTime();

    protected GroupRepository() {}

    final void bind(ChangeSet changes) {
        entityType = changes.type;
        mapper = changes.mapper;
        this.changes = changes;
    }

    /** A live, read-only view of the group, loaded on this thread when not in memory; empty when the database has none. */
    public final Map<K, E> getGroup(GroupKeys groupKey) { return group(entityType().checkGroupKey(groupKey)).view; }

    /** The entity at {@code mapKey} in the group, or null. */
    public final E get(GroupKeys groupKey, K mapKey) {
        entityType().checkKeyType(mapKey);
        return group(entityType.checkGroupKey(groupKey)).entities.get(mapKey);
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
        Object id = entityType().idOf(entity);
        GroupKeys groupKey = entityType.groupKeyOf(entity);
        group(groupKey).entities.compute(mapKeyOf(entity), (key, existing) -> {
            if (existing != null) throw new IllegalStateException(entityType.entityClass().getSimpleName() + " " + key + " already exists in group " + groupKey);
            changes.add(INSERT, id, groupKey, key, entity);
            return entity;
        });
    }

    /**
     * Records that the entity changed, to be written with the state it has when the round writes it.
     * IllegalStateException when its group has no entity at its map key, or another one.
     */
    public final void update(E entity) {
        Object id = entityType().idOf(entity);
        GroupKeys groupKey = entityType.groupKeyOf(entity);
        K mapKey = mapKeyOf(entity);
        Group<K, E> group = group(groupKey);
        if (group.entities.get(mapKey) == entity) {   // the usual case: the entity in memory changed
            changes.add(UPDATE, id, groupKey, mapKey, entity);
            return;
        }
        group.entities.compute(mapKey, (key, existing) -> {
            if (existing == null) throw new IllegalStateException(entityType.entityClass().getSimpleName() + " " + key + " does not exist in group " + groupKey);
            if (!id.equals(entityType.idOf(existing)))
                throw new IllegalStateException(entityType.entityClass().getSimpleName() + " " + key + " of group " + groupKey + " is " + entityType.idOf(existing) + ", not " + id);
            changes.add(UPDATE, id, groupKey, key, entity);
            return entity;
        });
    }

    /** Deletes the entity at the entity's map key from its group; nothing when there is none. */
    public final void delete(E entity) {
        GroupKeys groupKey = entityType().groupKeyOf(entity);
        delete(group(groupKey), groupKey, mapKeyOf(entity));
    }

    /** Deletes every entity of the group. */
    public final void deleteGroup(GroupKeys groupKey) {
        Group<K, E> group = group(entityType().checkGroupKey(groupKey));
        for (K mapKey : new ArrayList<>(group.entities.keySet())) delete(group, groupKey, mapKey);
    }

    /** Drops the group from memory; its changes not yet written are written all the same. */
    public final void evict(GroupKeys groupKey) { cache.remove(entityType().checkGroupKey(groupKey)); }

    final void setAccessTime(long time) { accessTime = time; }

    /** Drops groups last accessed before {@code time}; runs on GameData's scheduler thread. */
    final void evictUnusedSince(long time) {
        for (var cached : cache.entrySet())
            if (cached.getValue().lastAccess - time < 0)
                cache.computeIfPresent(cached.getKey(), (key, group) -> group.lastAccess - time < 0 ? null : group);
    }

    private void delete(Group<K, E> group, GroupKeys groupKey, K mapKey) {
        group.entities.computeIfPresent(mapKey, (key, existing) -> {
            changes.add(DELETE, entityType.idOf(existing), groupKey, key, existing);
            return null;
        });
    }

    private Group<K, E> group(GroupKeys groupKey) {
        Group<K, E> group = cache.get(groupKey);
        if (group == null) return load(groupKey);
        long time = accessTime;
        if (group.lastAccess != time) group.lastAccess = time;
        return group;
    }

    /**
     * Loads outside any lock and lays the group's unwritten changes over what the database returned, deletes first, so
     * a row deleted and another inserted at the same map key end as the inserted one. Changes always go to the group
     * in memory and are made only after loading it, so a group another thread put in memory meanwhile wins.
     */
    @SuppressWarnings("unchecked")
    private Group<K, E> load(GroupKeys groupKey) {
        List<Change> unwritten = changes.findGroup(groupKey);
        List<Object> loaded;
        try {
            loaded = mapper.loadGroup(entityType, groupKey);
        } catch (Exception e) {
            throw new DataException(DATA_LOAD_FAILED, "cannot load " + entityType.entityClass().getSimpleName() + " group " + groupKey, e);
        }
        Group<K, E> group = new Group<>(accessTime);
        for (Object entity : loaded) {
            K mapKey = mapKeyOf((E) entity);
            if (group.entities.putIfAbsent(mapKey, (E) entity) != null)
                throw new DataException(DATA_LOAD_FAILED, "group " + groupKey + " of " + entityType.entityClass().getSimpleName()
                        + " has two rows with map key " + mapKey, null);
        }
        for (Change change : unwritten) if (change.operation == DELETE) group.entities.remove((K) change.mapKey);
        for (Change change : unwritten)
            if (change.operation != DELETE) group.entities.put((K) change.mapKey, (E) change.entity);
        Group<K, E> existing = cache.putIfAbsent(groupKey, group);
        return existing != null ? existing : group;
    }

    @SuppressWarnings("unchecked")
    private K mapKeyOf(E entity) { return (K) entityType.mapKeyOf(entity); }

    private EntityType entityType() {
        if (entityType == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        return entityType;
    }
}
