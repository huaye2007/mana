package cn.managame.ecs;

/**
 * Stable identifier of an entity inside an {@link EcsWorld}.
 *
 * @param value positive numeric identifier
 */
public record EntityId(long value) implements Comparable<EntityId> {

    public EntityId {
        if (value <= 0) {
            throw new IllegalArgumentException("Entity id must be positive: " + value);
        }
    }

    @Override
    public int compareTo(EntityId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
