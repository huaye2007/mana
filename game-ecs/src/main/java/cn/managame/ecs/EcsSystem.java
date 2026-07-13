package cn.managame.ecs;

/** A unit of world logic executed by {@link SystemPipeline}. */
@FunctionalInterface
public interface EcsSystem {

    void update(World world, long deltaNanos, CommandBuffer commands);

    default SystemPhase phase() {
        return SystemPhase.SIMULATION;
    }

    default int order() {
        return 0;
    }
}
