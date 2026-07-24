package cn.managame.ecs;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsWorldTest {

    @Test
    void maintainsEntityLifecycleComponentsAndQueryIndexes() {
        try (EcsWorld world = new EcsWorld()) {
            Entity moving = world.createEntity()
                    .add(new Position(10, 20))
                    .add(new Velocity(2, -1));
            Entity stationary = world.createEntity().add(new Position(3, 4));

            assertEquals(2, world.entityCount());
            assertEquals(List.of(moving, stationary), world.query(Position.class).entities());
            assertEquals(List.of(moving),
                    world.query(Position.class, Velocity.class).entities());
            assertEquals(new Position(10, 20), moving.require(Position.class));

            moving.add(new Position(11, 21));
            assertEquals(new Position(11, 21), moving.require(Position.class));
            assertEquals(2, moving.componentTypes().size());

            assertEquals(new Velocity(2, -1), moving.remove(Velocity.class).orElseThrow());
            assertTrue(world.query(Position.class, Velocity.class).isEmpty());
            assertFalse(moving.has(Velocity.class));

            assertTrue(stationary.destroy());
            assertFalse(stationary.isAlive());
            assertFalse(stationary.destroy());
            assertThrows(IllegalStateException.class,
                    () -> stationary.find(Position.class));
            assertEquals(1, world.entityCount());
        }
    }

    @Test
    void supportsExplicitComponentKeys() {
        try (EcsWorld world = new EcsWorld()) {
            Entity entity = world.createEntity()
                    .add(DisplayName.class, new PlayerName("mage"));

            assertEquals("mage", entity.require(DisplayName.class).value());
            assertFalse(entity.find(PlayerName.class).isPresent(),
                    "component lookup uses the exact storage key");
            assertEquals(List.of(entity), world.query(DisplayName.class).entities());
        }
    }

    @Test
    void queryIteratorsAreSnapshotsAndEntityIdsAreNeverReused() {
        try (EcsWorld world = new EcsWorld()) {
            Entity first = world.createEntity().add(new Position(1, 1));
            Iterator<Entity> snapshot = world.query(Position.class).iterator();

            Entity second = world.createEntity().add(new Position(2, 2));
            assertEquals(List.of(first), snapshotToList(snapshot));
            assertEquals(List.of(first, second), world.query(Position.class).entities());

            EntityId removedId = first.id();
            first.destroy();
            Entity replacement = world.createEntity();
            assertNotEquals(removedId, replacement.id());
        }
    }

    @Test
    void validatesWorldAndEntityInputs() {
        EcsWorld world = new EcsWorld();
        Entity entity = world.createEntity();

        assertThrows(IllegalArgumentException.class, () -> world.update(-0.01));
        assertThrows(IllegalArgumentException.class,
                () -> world.update(Double.NaN));
        assertThrows(NullPointerException.class, () -> world.query((Class<Component>) null));
        assertSame(entity, world.requireEntity(entity.id()));

        world.close();
        assertFalse(entity.isAlive());
        assertThrows(IllegalStateException.class, world::createEntity);
        assertThrows(IllegalStateException.class,
                () -> world.commands().destroyEntity(entity.id()));
    }

    private static List<Entity> snapshotToList(Iterator<Entity> iterator) {
        java.util.ArrayList<Entity> result = new java.util.ArrayList<>();
        iterator.forEachRemaining(result::add);
        return result;
    }

    private record Position(int x, int y) implements Component {
    }

    private record Velocity(int x, int y) implements Component {
    }

    private interface DisplayName extends Component {

        String value();
    }

    private record PlayerName(String value) implements DisplayName {
    }
}
