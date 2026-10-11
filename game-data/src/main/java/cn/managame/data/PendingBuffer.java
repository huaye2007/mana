package cn.managame.data;

import cn.managame.data.meta.EntityMeta;
import cn.managame.data.error.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dirty state per entity id. Writers merge into {@code pending}; the single flush thread claims an
 * entry by conditional removal, so a newer record made after the claim stays pending for the next
 * flush and nothing is lost between iteration and removal. Repositories never evict an entity that is
 * pending, so loads never consult this buffer.
 */
final class PendingBuffer {
    /** Identity equality is required: claim uses remove(id, change). */
    static final class Change {
        private final DataOperation operation;
        private final Object entity;
        Change(DataOperation operation, Object entity) { this.operation = operation; this.entity = entity; }
        DataOperation operation() { return operation; }
        /** The latest entity; may be null for a delete recorded by id only. */
        Object entity() { return entity; }
    }
    record Claimed(Object id, Change change) {}

    private final Map<EntityMeta, ConcurrentHashMap<Object, Change>> pending = new LinkedHashMap<>();

    PendingBuffer(Set<EntityMeta> metas) {
        for (EntityMeta meta : metas) pending.put(meta, new ConcurrentHashMap<>());
    }
    Set<EntityMeta> metas() { return pending.keySet(); }

    void record(EntityMeta meta, Object id, DataOperation operation, Object entity) {
        pending.get(meta).compute(id, (key, old) -> merge(old, operation, entity));
    }

    /** Claims every change currently pending for meta. Called only by the flush thread. */
    List<Claimed> claim(EntityMeta meta) {
        var source = pending.get(meta);
        List<Claimed> claimed = new ArrayList<>();
        for (var entry : source.entrySet()) {
            Object id = entry.getKey(); Change change = entry.getValue();
            // Superseded entries stay pending: the newer change is saved by the next flush.
            if (source.remove(id, change)) claimed.add(new Claimed(id, change));
        }
        return claimed;
    }

    /**
     * Retryable failure: merge the older claimed change underneath any newer pending change.
     * Returns false when the sequence is illegal (the newer change is kept, the older is dropped).
     */
    boolean requeue(EntityMeta meta, Claimed claimed) {
        boolean[] legal = {true};
        pending.get(meta).compute(claimed.id(), (key, newer) -> {
            if (newer == null) return claimed.change();
            try { return combine(claimed.change(), newer); }
            catch (DataOperationException illegal) { legal[0] = false; return newer; }
        });
        return legal[0];
    }

    boolean pending(EntityMeta meta, Object id) { return pending.get(meta).containsKey(id); }
    Collection<Change> pending(EntityMeta meta) { return pending.get(meta).values(); }
    long size() {
        long count = 0;
        for (var map : pending.values()) count += map.size();
        return count;
    }

    static Change combine(Change older, Change newer) {
        if (newer.operation() == DataOperation.DELETE_INSERT) {
            Change deleted = merge(older, DataOperation.DELETE, newer.entity());
            return merge(deleted, DataOperation.INSERT, newer.entity());
        }
        return merge(older, newer.operation(), newer.entity());
    }
    static Change merge(Change old, DataOperation op, Object entity) {
        if (old == null) return new Change(op, entity);
        return switch (old.operation()) {
            case INSERT -> switch (op) {
                case UPDATE -> new Change(DataOperation.INSERT, entity);
                case DELETE -> null;
                default -> throw invalid(old, op);
            };
            case UPDATE -> switch (op) {
                case UPDATE, DELETE -> new Change(op, entity);
                default -> throw invalid(old, op);
            };
            case DELETE -> switch (op) {
                case INSERT -> new Change(DataOperation.DELETE_INSERT, entity);
                case DELETE -> old;
                default -> throw invalid(old, op);
            };
            case DELETE_INSERT -> switch (op) {
                case UPDATE -> new Change(DataOperation.DELETE_INSERT, entity);
                case DELETE -> new Change(op, entity);
                default -> throw invalid(old, op);
            };
            default -> throw invalid(old, op);
        };
    }
    private static DataOperationException invalid(Change old, DataOperation op) {
        return new DataOperationException("Illegal change sequence: " + old.operation() + " -> " + op);
    }
}
