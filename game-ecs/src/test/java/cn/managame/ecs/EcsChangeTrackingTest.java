package cn.managame.ecs;

import cn.managame.ecs.change.ComponentChangeKind;
import cn.managame.ecs.change.FieldChange;
import cn.managame.ecs.change.WorldChange;
import cn.managame.ecs.change.WorldChangeBatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcsChangeTrackingTest {

    @Test
    void trackingIsOptInAndFieldUpdatesAreCoalesced() {
        try (EcsWorld world = new EcsWorld()) {
            Entity entity = world.createEntity().add(new Stats(100, 50));
            assertFalse(world.isChangeTrackingEnabled());
            assertTrue(world.drainChanges().isEmpty());

            world.enableChangeTracking();
            entity.updateField(
                    Stats.class, "hp", Stats::hp, Stats::setHp, 90);
            entity.updateField(
                    Stats.class, "hp", Stats::hp, Stats::setHp, 80);
            entity.updateField(
                    Stats.class, "mana", Stats::mana, Stats::setMana, 40);
            entity.updateField(
                    Stats.class, "mana", Stats::mana, Stats::setMana, 50);

            WorldChangeBatch batch = world.drainChanges().orElseThrow();
            assertEquals(1, batch.sequence());
            assertEquals(1, batch.changes().size());

            WorldChange.ComponentChanged changed = assertInstanceOf(
                    WorldChange.ComponentChanged.class, batch.changes().getFirst());
            assertEquals(ComponentChangeKind.FIELDS_UPDATED, changed.kind());
            assertSame(entity.require(Stats.class), changed.currentComponent());
            assertEquals(
                    new FieldChange("hp", 100, 80),
                    changed.fields().get("hp"));
            assertFalse(changed.fields().containsKey("mana"),
                    "a field returning to its original value should cancel out");
            assertEquals(80, entity.require(Stats.class).hp());
        }
    }

    @Test
    void coalescesStructuralChangesToTheFinalClientVisibleState() {
        try (EcsWorld world = new EcsWorld().enableChangeTracking()) {
            Entity transientEntity = world.createEntity().add(new Health(1));
            transientEntity.destroy();
            assertTrue(world.drainChanges().isEmpty(),
                    "an entity created and destroyed before delivery is invisible");

            Entity entity = world.createEntity().add(new Health(10));
            WorldChangeBatch initial = world.drainChanges().orElseThrow();
            assertEquals(2, initial.changes().size());
            assertInstanceOf(
                    WorldChange.EntityCreated.class, initial.changes().get(0));
            assertEquals(
                    ComponentChangeKind.ADDED,
                    assertInstanceOf(
                            WorldChange.ComponentChanged.class,
                            initial.changes().get(1)).kind());

            entity.add(new Health(20));
            entity.add(new Health(30));
            WorldChange.ComponentChanged replacement = assertInstanceOf(
                    WorldChange.ComponentChanged.class,
                    world.drainChanges().orElseThrow().changes().getFirst());
            assertEquals(ComponentChangeKind.REPLACED, replacement.kind());
            assertEquals(new Health(10), replacement.previousComponent());
            assertEquals(new Health(30), replacement.currentComponent());

            entity.remove(Health.class);
            entity.add(new Health(30));
            assertTrue(world.drainChanges().isEmpty(),
                    "remove followed by an equal add should cancel out");
        }
    }

    @Test
    void publishesOneMergedBatchToSinksAtTickEnd() {
        List<WorldChangeBatch> delivered = new ArrayList<>();
        try (EcsWorld world = new EcsWorld()) {
            world.addChangeSink(delivered::add);
            Entity entity = world.createEntity().add(new Stats(100, 50));
            assertTrue(world.publishChanges());
            delivered.clear();

            world.addSystem((current, deltaSeconds) -> {
                entity.updateField(
                        Stats.class, "hp", Stats::hp, Stats::setHp, 95);
                entity.updateField(
                        Stats.class, "hp", Stats::hp, Stats::setHp, 75);
            });

            world.update(0.05);

            assertEquals(1, delivered.size());
            WorldChangeBatch batch = delivered.getFirst();
            assertEquals(2, batch.sequence());
            assertEquals(1, batch.changes().size());
            WorldChange.ComponentChanged changed = assertInstanceOf(
                    WorldChange.ComponentChanged.class, batch.changes().getFirst());
            assertEquals(
                    new FieldChange("hp", 100, 75),
                    changed.fields().get("hp"));

            world.update(0.05);
            assertEquals(1, delivered.size(),
                    "empty ticks should not invoke change sinks");
        }
    }

    @Test
    void supportsManualSnapshotsForInPlaceCollectionMutations() {
        try (EcsWorld world = new EcsWorld().enableChangeTracking()) {
            Inventory inventory = new Inventory();
            Entity entity = world.createEntity().add(inventory);
            world.drainChanges();

            List<String> previous = List.copyOf(inventory.items());
            inventory.items().add("sword");
            entity.markChanged(
                    Inventory.class,
                    "items",
                    previous,
                    List.copyOf(inventory.items()));

            WorldChange.ComponentChanged changed = assertInstanceOf(
                    WorldChange.ComponentChanged.class,
                    world.drainChanges().orElseThrow().changes().getFirst());
            assertEquals(
                    new FieldChange("items", List.of(), List.of("sword")),
                    changed.fields().get("items"));
        }
    }

    private record Health(int value) implements Component {
    }

    private static final class Stats implements Component {

        private int hp;
        private int mana;

        private Stats(int hp, int mana) {
            this.hp = hp;
            this.mana = mana;
        }

        private int hp() {
            return hp;
        }

        private void setHp(int hp) {
            this.hp = Math.max(0, hp);
        }

        private int mana() {
            return mana;
        }

        private void setMana(int mana) {
            this.mana = Math.max(0, mana);
        }
    }

    private static final class Inventory implements Component {

        private final List<String> items = new ArrayList<>();

        private List<String> items() {
            return items;
        }
    }
}
