package cn.managame.ecs;

/** Lightweight lifecycle hook used to bridge ECS changes to persistence or events. */
public interface WorldListener {

    default void onEntityCreated(Entity entity) {
    }

    default void onEntityDeleted(Entity entity) {
    }

    default void onComponentAdded(Entity entity, Component component) {
    }

    default void onComponentRemoved(Entity entity, Component component) {
    }
}
