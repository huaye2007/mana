package cn.managame.data;

import cn.managame.data.meta.EntityMeta;
import cn.managame.data.error.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class PendingBuffer {
    record Change(DataOperation operation, Object entity) {}
    final Map<EntityMeta, ConcurrentHashMap<Object, Change>> changes = new LinkedHashMap<>();
    PendingBuffer(Set<EntityMeta> metas) {
        for (EntityMeta meta : metas) changes.put(meta, new ConcurrentHashMap<>());
    }
    void record(EntityMeta meta, Object id, DataOperation operation, Object entity) {
        changes.get(meta).compute(id, (key, old) -> merge(old, operation, entity));
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
                case DELETE -> new Change(op, null);
                default -> throw invalid(old, op);
            };
            default -> throw invalid(old, op);
        };
    }
    private static DataOperationException invalid(Change old, DataOperation op) {
        return new DataOperationException("Illegal change sequence: " + old.operation() + " -> " + op);
    }
}
