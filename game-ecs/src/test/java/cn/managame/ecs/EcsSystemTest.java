package cn.managame.ecs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsSystemTest {

    @Test
    void runsSystemsByOrderAndLifecycleCallbacksInReverseOnClose() {
        List<String> events = new ArrayList<>();
        EcsWorld world = new EcsWorld();
        TrackingSystem late = new TrackingSystem("late", 20, events);
        TrackingSystem early = new TrackingSystem("early", -20, events);

        world.addSystem(late).addSystem(early);
        events.clear();
        world.update(0.05);
        assertEquals(List.of("update:early", "update:late"), events);

        events.clear();
        world.close();
        assertEquals(List.of("removed:late", "removed:early"), events);
    }

    @Test
    void keepsRegistrationOrderWhenSystemOrdersMatch() {
        List<String> events = new ArrayList<>();
        try (EcsWorld world = new EcsWorld()) {
            world.addSystem(new TrackingSystem("one", 0, events));
            world.addSystem(new TrackingSystem("two", 0, events));
            events.clear();

            world.update(0);

            assertEquals(List.of("update:one", "update:two"), events);
        }
    }

    @Test
    void rejectsDuplicateAndStructuralSystemChangesDuringUpdate() {
        try (EcsWorld world = new EcsWorld()) {
            TrackingSystem duplicate = new TrackingSystem("duplicate", 0, new ArrayList<>());
            world.addSystem(duplicate);
            assertThrows(IllegalStateException.class, () -> world.addSystem(duplicate));

            AtomicBoolean rejected = new AtomicBoolean();
            world.addSystem((current, delta) -> {
                try {
                    current.removeSystem(duplicate);
                } catch (IllegalStateException expected) {
                    rejected.set(true);
                }
            });

            world.update(0.01);
            assertTrue(rejected.get());
        }
    }

    @Test
    void flushesDeferredChangesOnlyAfterAllSystemsFinish() {
        try (EcsWorld world = new EcsWorld()) {
            Entity oldEntity = world.createEntity().add(new Health(10));
            AtomicInteger countSeenBySecondSystem = new AtomicInteger();
            EntityId[] futureId = new EntityId[1];

            world.addSystem(new EntitySystem() {
                @Override
                public int order() {
                    return -10;
                }

                @Override
                public void update(EcsWorld current, double deltaSeconds) {
                    current.commands().destroyEntity(oldEntity.id());
                    futureId[0] = current.commands()
                            .createEntity(entity -> entity.add(new Health(100)));
                    assertTrue(oldEntity.isAlive());
                    assertFalse(current.isAlive(futureId[0]));
                }
            });
            world.addSystem((current, deltaSeconds) ->
                    countSeenBySecondSystem.set(current.query(Health.class).count()));

            world.update(0.05);

            assertEquals(1, countSeenBySecondSystem.get());
            assertFalse(oldEntity.isAlive());
            assertTrue(world.isAlive(futureId[0]));
            assertEquals(100,
                    world.requireEntity(futureId[0]).require(Health.class).value());
            assertEquals(0, world.commands().pendingCount());
        }
    }

    @Test
    void flushesQueuedChangesEvenWhenASystemFails() {
        try (EcsWorld world = new EcsWorld()) {
            world.addSystem((current, deltaSeconds) -> {
                current.commands().createEntity(entity -> entity.add(new Health(7)));
                throw new IllegalStateException("tick failed");
            });

            IllegalStateException failure =
                    assertThrows(IllegalStateException.class, () -> world.update(0.1));

            assertEquals("tick failed", failure.getMessage());
            assertEquals(1, world.query(Health.class).count());
        }
    }

    private record Health(int value) implements Component {
    }

    private static final class TrackingSystem implements EntitySystem {

        private final String name;
        private final int order;
        private final List<String> events;

        private TrackingSystem(String name, int order, List<String> events) {
            this.name = name;
            this.order = order;
            this.events = events;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public void onAdded(EcsWorld world) {
            events.add("added:" + name);
        }

        @Override
        public void update(EcsWorld world, double deltaSeconds) {
            events.add("update:" + name);
        }

        @Override
        public void onRemoved(EcsWorld world) {
            events.add("removed:" + name);
        }
    }
}
