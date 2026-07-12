package cn.managame.ecs;

/** A world-local entity identifier. IDs are never reused by a world. */
public record Entity(long id) {

    public Entity {
        if (id <= 0) {
            throw new IllegalArgumentException("entity id must be positive: " + id);
        }
    }
}
