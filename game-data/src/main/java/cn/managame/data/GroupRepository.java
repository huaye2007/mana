package cn.managame.data;

import cn.managame.data.error.*;
import cn.managame.data.key.GroupKey;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import com.github.benmanes.caffeine.cache.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class GroupRepository<K, E> {
    private EntityMeta meta;
    private EntityMapper mapper;
    private WriteBehindManager writer;
    private LoadingCache<GroupKey, ConcurrentHashMap<K,E>> cache;
    protected GroupRepository() {}
    @SuppressWarnings("unchecked")
    final void initialize(EntityMeta meta, EntityMapper mapper, WriteBehindManager writer, Duration expiry) {
        this.meta = meta; this.mapper = mapper; this.writer = writer;
        cache = Caffeine.newBuilder().expireAfterAccess(expiry).build(key -> {
            try {
                var result = new ConcurrentHashMap<K,E>();
                for (Object entity : this.mapper.loadGroup(meta, key)) {
                    if (!key.equals(meta.getGroupKey(entity)))
                        throw new DataLoadException("Mapper returned an entity from another group");
                    if (result.putIfAbsent((K) meta.getMapKey(entity), (E) entity) != null)
                        throw new DataLoadException("Duplicate MapKey in loaded group");
                }
                return result;
            } catch (Exception e) { throw new DataLoadException("Group load failed: " + meta.entityType(), e); }
        });
    }
    private void ready() {
        if (writer == null) throw new DataOperationException("Repository is not initialized");
        writer.ensureRunning();
    }
    public final Map<K,E> getGroup(GroupKey key) {
        ready(); meta.validateGroupKey(key); return cache.get(key);
    }
    public final E get(GroupKey key, K mapKey) {
        ready();
        if (Objects.requireNonNull(mapKey).getClass() != meta.keyType())
            throw new DataOperationException("MapKey type mismatch");
        return getGroup(key).get(mapKey);
    }
    private ConcurrentHashMap<K,E> loaded(GroupKey key) {
        var group = cache.getIfPresent(key);
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
        writer.mutate(() -> {
            var group = loaded(groupKey);
            writer.record(meta, id, operation, operation == DataOperation.DELETE ? null : entity);
            if (operation == DataOperation.DELETE) group.remove(mapKey);
            else group.put(mapKey, entity);
        });
    }
    public final void deleteGroup(GroupKey key) {
        ready(); meta.validateGroupKey(key);
        writer.mutate(() -> {
            var group = loaded(key);
            for (E entity : group.values())
                writer.record(meta, meta.getId(entity), DataOperation.DELETE, null);
            group.clear();
        });
    }
}
