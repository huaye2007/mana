package cn.managame.ecs;

/**
 * Behavior executed by an {@link EcsWorld} on every simulation tick.
 *
 * <p>Systems with a lower {@link #order()} run first. Systems with the same
 * order retain registration order.</p>
 */
public interface EntitySystem {

    /**
     * Returns the execution order of this system.
     */
    default int order() {
        return 0;
    }

    /**
     * Called once after this system is registered.
     */
    default void onAdded(EcsWorld world) {
    }

    /**
     * Advances this system by one simulation tick.
     *
     * @param world world being updated
     * @param deltaSeconds elapsed simulation time in seconds
     */
    void update(EcsWorld world, double deltaSeconds);

    /**
     * Called once before this system is unregistered or the world is closed.
     */
    default void onRemoved(EcsWorld world) {
    }
}
