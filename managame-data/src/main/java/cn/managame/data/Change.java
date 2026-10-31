package cn.managame.data;

/**
 * A change of one row waiting to be written, as an {@link EntityMapper} receives it. Its operation and entity change
 * only while it waits, under its change set's lock, never while a mapper writes it.
 */
public final class Change {
    final Object id, mapKey;
    final GroupKeys group;
    DataOperation operation;
    Object entity;

    Change(DataOperation operation, Object id, GroupKeys group, Object mapKey, Object entity) {
        this.operation = operation;
        this.id = id;
        this.group = group;
        this.mapKey = mapKey;
        this.entity = entity;
    }

    /** The entity's {@code @Id}. */
    public Object id() { return id; }

    /** The entity, to be read now for its current state; null only for a delete by id of an entity not in memory. */
    public Object entity() { return entity; }

    /** What a failure report names: the entity, or its id when there is none. */
    Object entityOrId() { return entity != null ? entity : id; }

    Change copy() { return new Change(operation, id, group, mapKey, entity); }
}
