package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.key.GroupKey;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached groups of entities sharing a {@code @GroupKey}, each group a map by {@code @MapKey}. A group is loaded
 * whole on first use, by reads and writes alike, so business code never has to load it before writing.
 *
 * <p>{@link #getGroup} returns a read-only live view: change entities only through insert/update/delete, which
 * are checked against the group immediately ({@code insert} of an existing MapKey and {@code update} of a
 * missing one throw {@link DataOperationException}; {@code delete} of a missing one does nothing).
 */
public abstract class GroupRepository<K, E> {
    /** The cached map and the read-only view handed out for it, created once per load. */
    private static final class Group<K, E> {
        final ConcurrentHashMap<K, E> entries;
        final Map<K, E> view;
        Group(ConcurrentHashMap<K, E> entries) { this.entries = entries; view = Collections.unmodifiableMap(entries); }
    }

    private EntityMeta meta;
    private EntityMapper mapper;
    private WriteBehindManager writer;
    private EntityCache<GroupKey, Group<K, E>> cache;
    protected GroupRepository() {}
    final long cachedEntries() { return cache.size(); }
    final void initialize(EntityMeta meta, EntityMapper mapper, WriteBehindManager writer, Duration expiry) {
        this.meta = meta; this.mapper = mapper; this.writer = writer;
        cache = new EntityCache<>(expiry.toNanos());
        writer.addSweeper(() -> cache.sweep(() -> {
            Set<GroupKey> dirty = new HashSet<>();
            for (PendingBuffer.Change change : writer.pending(meta)) dirty.add(meta.getGroupKey(change.entity()));
            return dirty::contains;
        }));
    }
    @SuppressWarnings("unchecked")
    private Group<K, E> load(GroupKey key) {
        // Only groups without unsaved changes are ever missing from the cache, so storage is current.
        try {
            var result = new ConcurrentHashMap<K, E>();
            for (Object entity : mapper.loadGroup(meta, key)) {
                if (!key.equals(meta.getGroupKey(entity)))
                    throw new DataLoadException("Mapper returned an entity from another group");
                if (result.putIfAbsent((K) meta.getMapKey(entity), (E) entity) != null)
                    throw new DataLoadException("Duplicate MapKey in loaded group");
            }
            return new Group<>(result);
        } catch (Exception e) { throw new DataLoadException("Group load failed: " + meta.entityType(), e); }
    }
    private void ready() {
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        writer.ensureRunning();
    }
    private Group<K, E> group(GroupKey key) { return cache.get(key, this::load); }

    /** Read-only live view of the group, loading it on this thread when missing; empty when storage has none. */
    public final Map<K, E> getGroup(GroupKey key) {
        ready(); meta.validateGroupKey(key); return group(key).view;
    }
    public final E get(GroupKey key, K mapKey) {
        ready(); meta.validateGroupKey(key);
        if (Objects.requireNonNull(mapKey).getClass() != meta.keyType())
            throw new DataOperationException("MapKey type mismatch");
        return group(key).entries.get(mapKey);
    }
    private static <G> G loaded(G group, GroupKey key) {
        // The group was loaded (and touched) just before, so only an impossible idle eviction could get here.
        if (group == null) throw new DataOperationException("Group was evicted during a mutation: " + key);
        return group;
    }
    public final void insert(E entity) { change(entity, DataOperation.INSERT); }
    public final void update(E entity) { change(entity, DataOperation.UPDATE); }
    public final void delete(E entity) { change(entity, DataOperation.DELETE); }
    @SuppressWarnings("unchecked")
    private void change(E entity, DataOperation operation) {
        ready(); Objects.requireNonNull(entity);
        GroupKey groupKey = meta.getGroupKey(entity);
        K mapKey = (K) meta.getMapKey(entity);
        group(groupKey);
        writer.mutate(() -> cache.update(groupKey, (cached, current) -> {
            var group = loaded(current, groupKey).entries;
            E existing = group.get(mapKey);
            switch (operation) {
                case INSERT -> {
                    if (existing != null) throw new DataOperationException("MapKey already exists in group " + groupKey + ": " + mapKey);
                    writer.record(meta, meta.getId(entity), operation, entity);
                    group.put(mapKey, entity);
                }
                case UPDATE -> {
                    if (existing == null) throw new DataOperationException("MapKey does not exist in group " + groupKey + ": " + mapKey);
                    Object id = meta.getId(entity);
                    if (existing != entity && !id.equals(meta.getId(existing)))
                        throw new DataOperationException("MapKey " + mapKey + " belongs to another @Id in group " + groupKey);
                    writer.record(meta, id, operation, entity);
                    group.put(mapKey, entity);
                }
                default -> {
                    if (existing == null) return current; // already absent
                    // Deletes keep the cached entity so sweeps can find its group.
                    writer.record(meta, meta.getId(existing), operation, existing);
                    group.remove(mapKey);
                }
            }
            return current;
        }));
    }
    public final void deleteGroup(GroupKey key) {
        ready(); meta.validateGroupKey(key);
        group(key);
        writer.mutate(() -> cache.update(key, (cached, current) -> {
            var group = loaded(current, key).entries;
            for (E entity : group.values())
                writer.record(meta, meta.getId(entity), DataOperation.DELETE, entity);
            group.clear();
            return current;
        }));
    }
}
