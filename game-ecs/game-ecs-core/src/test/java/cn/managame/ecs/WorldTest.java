package cn.managame.ecs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTest {

    record Position(int x, int y) implements Component {
    }

    record Velocity(int x, int y) implements Component {
    }

    @Test
    void managesComponentsAndQueriesByIntersection() {
        World world = new World();
        Entity moving = world.createEntity();
        Entity stationary = world.createEntity();
        world.add(moving, new Position(1, 2));
        world.add(moving, new Velocity(3, 4));
        world.add(stationary, new Position(5, 6));

        assertEquals(List.of(moving, stationary), world.query(Position.class));
        assertEquals(List.of(moving), world.query(Position.class, Velocity.class));
        assertEquals(new Position(1, 2), world.get(moving, Position.class).orElseThrow());
        assertTrue(world.has(moving, Velocity.class));

        world.remove(moving, Velocity.class);
        assertTrue(world.query(Position.class, Velocity.class).isEmpty());
        assertFalse(world.has(moving, Velocity.class));
    }

    @Test
    void snapshotsQueriesAndRejectsChangesToDeadEntities() {
        World world = new World();
        Entity entity = world.createEntity();
        world.add(entity, new Position(1, 1));
        List<Entity> snapshot = world.query(Position.class);

        assertTrue(world.deleteEntity(entity));
        assertEquals(List.of(entity), snapshot);
        assertFalse(world.deleteEntity(entity));
        assertThrows(IllegalArgumentException.class, () -> world.add(entity, new Velocity(1, 1)));
    }

    @Test
    void emitsLifecycleEventsInStructuralOrder() {
        World world = new World();
        List<String> events = new ArrayList<>();
        world.addListener(new WorldListener() {
            @Override
            public void onEntityCreated(Entity entity) {
                events.add("create:" + entity.id());
            }

            @Override
            public void onComponentAdded(Entity entity, Component component) {
                events.add("add:" + component.getClass().getSimpleName());
            }

            @Override
            public void onComponentRemoved(Entity entity, Component component) {
                events.add("remove:" + component.getClass().getSimpleName());
            }

            @Override
            public void onEntityDeleted(Entity entity) {
                events.add("delete:" + entity.id());
            }
        });

        Entity entity = world.createEntity();
        world.add(entity, new Position(0, 0));
        world.add(entity, new Position(1, 1));
        world.deleteEntity(entity);

        assertEquals(List.of("create:1", "add:Position", "remove:Position", "add:Position",
                "remove:Position", "delete:1"), events);
    }
}
