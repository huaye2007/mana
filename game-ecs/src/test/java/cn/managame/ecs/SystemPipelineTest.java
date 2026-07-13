package cn.managame.ecs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemPipelineTest {

    record Position(int x) implements Component {
    }

    @Test
    void ordersSystemsAndAppliesCommandsBetweenSystems() {
        World world = new World();
        List<String> calls = new ArrayList<>();
        EcsSystem simulation = new OrderedSystem(SystemPhase.SIMULATION, 0, (w, commands) -> {
            calls.add("simulation");
            commands.spawn(new Position(7));
        });
        EcsSystem post = new OrderedSystem(SystemPhase.POST_SIMULATION, 0, (w, commands) -> {
            calls.add("post:" + w.query(Position.class).size());
        });
        EcsSystem input = new OrderedSystem(SystemPhase.INPUT, 10, (w, commands) -> calls.add("input"));

        new SystemPipeline().add(post).add(simulation).add(input).update(world, 16_000_000);

        assertEquals(List.of("input", "simulation", "post:1"), calls);
        assertEquals(1, world.entityCount());
    }

    @Test
    void commandBufferCanOnlyBeAppliedOnce() {
        World world = new World();
        CommandBuffer commands = world.commandBuffer().spawn(new Position(1));
        commands.apply();

        assertThrows(IllegalStateException.class, commands::apply);
        assertThrows(IllegalStateException.class, () -> commands.spawn(new Position(2)));
    }

    private record OrderedSystem(SystemPhase phase, int order, SystemBody body) implements EcsSystem {
        @Override
        public void update(World world, long deltaNanos, CommandBuffer commands) {
            body.run(world, commands);
        }
    }

    @FunctionalInterface
    private interface SystemBody {
        void run(World world, CommandBuffer commands);
    }
}
