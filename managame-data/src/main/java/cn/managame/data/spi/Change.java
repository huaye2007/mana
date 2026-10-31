package cn.managame.data.spi;

/** A change of one row waiting to be written, as an {@link EntityMapper} receives it in a batch. */
public interface Change {
    /** The entity's {@code @Id}. */
    Object id();

    /** The entity, to be read now for its current state; null only for a delete by id of an entity not in memory. */
    Object entity();
}
