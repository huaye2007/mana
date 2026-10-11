package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.key.GroupKey;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class GroupRepository<K, E> {
    private EntityMeta meta;
    private EntityMapper mapper;
    private WriteBehindManager writer;
    private EntityCache<GroupKey, ConcurrentHashMap<K,E>> cache;
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
    private ConcurrentHashMap<K,E> load(GroupKey key) {
        // Only groups without unsaved changes are ever missing from the cache, so storage is current.
        try {
            var result = new ConcurrentHashMap<K,E>();
            for (Object entity : mapper.loadGroup(meta, key)) {
                if (!key.equals(meta.getGroupKey(entity)))
                    throw new DataLoadException("Mapper returned an entity from another group");
                if (result.putIfAbsent((K) meta.getMapKey(entity), (E) entity) != null)
                    throw new DataLoadException("Duplicate MapKey in loaded group");
            }
            return result;
        } catch (Exception e) { throw new DataLoadException("Group load failed: " + meta.entityType(), e); }
    }
    private void ready() {
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        writer.ensureRunning();
    }
    public final Map<K,E> getGroup(GroupKey key) {
        ready(); meta.validateGroupKey(key); return cache.get(key, this::load);
    }
    public final E get(GroupKey key, K mapKey) {
        ready();
        if (Objects.requireNonNull(mapKey).getClass() != meta.keyType())
            throw new DataOperationException("MapKey type mismatch");
        return getGroup(key).get(mapKey);
    }
    private static <G> G loaded(G group, GroupKey key) {
        if (group == null) throw new DataOperationException("Group must be loaded before mutation: " + key);
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
        Object id = meta.getId(entity);
        writer.mutate(() -> cache.update(groupKey, current -> {
            var group = loaded(current, groupKey);
            writer.record(meta, id, operation, entity); // deletes keep the entity so sweeps can find its group
            if (operation == DataOperation.DELETE) group.remove(mapKey);
            else group.put(mapKey, entity);
            return group;
        }));
    }
    public final void deleteGroup(GroupKey key) {
        ready(); meta.validateGroupKey(key);
        writer.mutate(() -> cache.update(key, current -> {
            var group = loaded(current, key);
            for (E entity : group.values())
                writer.record(meta, meta.getId(entity), DataOperation.DELETE, entity);
            group.clear();
            return group;
        }));
    }
}
