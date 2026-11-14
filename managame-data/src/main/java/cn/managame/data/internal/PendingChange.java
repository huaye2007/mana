package cn.managame.data.internal;

import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import cn.managame.data.spi.Change;

/**
 * A row's change waiting in its {@link ChangeSet}, the only one of its row. Its operation, map key and entity change
 * only while it waits, under the change set's lock, never while a round writes it: a row changed meanwhile gets another
 * change in its place. Not API: it may change in any release.
 */
public final class PendingChange implements Change {
    final Object id;
    final GroupKeys group;
    Object mapKey;
    DataOperation operation;
    Object entity;
    /** Whether the round in progress took it; set and cleared under the change set's lock. */
    boolean writing;
    /** Set by the thread writing it once it is written or given up, so that the round's end removes it. */
    boolean finished;

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

    /** The row's map key within its group, as its entity has it now; null for an ungrouped type. */
    public Object mapKey() { return mapKey; }

    /** What a failure report names: the entity, or its id when there is none. */
    Object entityOrId() { return entity != null ? entity : id; }

    PendingChange copy() { return new PendingChange(operation, id, group, mapKey, entity); }
}
