package cn.managame.ecs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Ordered collection of ECS systems. Registration is intended for application startup. */
public final class SystemPipeline {

    private static final Comparator<EcsSystem> ORDER = Comparator
            .comparing(EcsSystem::phase)
            .thenComparingInt(EcsSystem::order);

    private final List<EcsSystem> systems = new ArrayList<>();

    public SystemPipeline add(EcsSystem system) {
        systems.add(Objects.requireNonNull(system, "system"));
        systems.sort(ORDER);
        return this;
    }

    public boolean remove(EcsSystem system) {
        return systems.remove(system);
    }

    public List<EcsSystem> systems() {
        return List.copyOf(systems);
    }

    /** Applies each successful system's deferred commands before the next system runs. */
    public void update(World world, long deltaNanos) {
        Objects.requireNonNull(world, "world");
        if (deltaNanos < 0) {
            throw new IllegalArgumentException("deltaNanos must not be negative: " + deltaNanos);
        }
        for (EcsSystem system : List.copyOf(systems)) {
            CommandBuffer commands = world.commandBuffer();
            system.update(world, deltaNanos, commands);
            commands.apply();
        }
    }
}
