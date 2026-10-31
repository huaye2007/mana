package cn.managame.data.internal;

import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import cn.managame.data.spi.Change;

/**
 * A row's change waiting in its {@link ChangeSet}. Its operation and entity change only while it waits, under the change
 * set's lock, never while a mapper writes it. Not API: it may change in any release.
 */
public final class PendingChange implements Change {
    final Object id, mapKey;
    final GroupKeys group;
    DataOperation operation;
    Object entity;

    PendingChange(DataOperation operation, Object id, GroupKeys group, Object mapKey, Object entity) {
        this.operation = operation;
        this.id = id;
        this.group = group;
        this.mapKey = mapKey;
        this.entity = entity;
    }

    @Override public Object id() { return id; }

    @Override public Object entity() { return entity; }

    public DataOperation operation() { return operation; }

    /** The row's map key within its group; null for an ungrouped type. */
    public Object mapKey() { return mapKey; }

    /** What a failure report names: the entity, or its id when there is none. */
    Object entityOrId() { return entity != null ? entity : id; }

    PendingChange copy() { return new PendingChange(operation, id, group, mapKey, entity); }
}
