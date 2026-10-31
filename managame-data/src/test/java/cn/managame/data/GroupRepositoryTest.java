package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.Fixtures.Item;
import cn.managame.data.Fixtures.Items;
import cn.managame.data.Fixtures.Quest;
import cn.managame.data.Fixtures.Quests;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GroupRepositoryTest {
    private final MemoryMapper database = new MemoryMapper();
    private final GameData data = GameData.builder().repositories(database, List.of(Items.class, Quests.class))
            .flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).build();
    private final Items items = data.repository(Items.class);
    private final Quests quests = data.repository(Quests.class);

    @AfterEach
    void close() { data.close(); }

    /** The stored items as uid, slot and amount. */
    private List<List<Object>> rows() {
        return database.rows(Item.class).stream().map(row -> List.of(row.get("uid"), row.get("slot"), row.get("amount"))).toList();
    }

    @Test
    void loadsAGroupWithOneQuery() {
        database.put(Item.class, new Item(1, 100, 0, 5001, 1));
        database.put(Item.class, new Item(2, 100, 1, 5002, 3));
        database.put(Item.class, new Item(3, 200, 0, 5001, 9));
        Map<Integer, Item> bag = items.getGroup(GroupKeys.of(100L));
        assertEquals(Set.of(0, 1), bag.keySet());
        assertEquals(3, bag.get(1).amount);
        assertSame(bag.get(0), items.get(GroupKeys.of(100L), 0));
        assertTrue(items.getGroup(GroupKeys.of(300L)).isEmpty());
    }

    @Test
    void writesChangesOfAGroup() {
        database.put(Item.class, new Item(1, 100, 0, 5001, 1));
        items.insert(new Item(2, 100, 1, 5002, 1));   // loads the group first
        assertEquals(2, items.getGroup(GroupKeys.of(100L)).size());
        assertThrows(IllegalStateException.class, () -> items.insert(new Item(3, 100, 1, 5003, 1)));
        assertTrue(items.update(GroupKeys.of(100L), 0, item -> item.amount = 7));
        assertFalse(items.update(GroupKeys.of(100L), 9, item -> item.amount = 7));
        items.delete(items.get(GroupKeys.of(100L), 1));
        data.flush();
        assertEquals(List.of(List.of(1L, 0, 7)), rows());

        items.deleteGroup(GroupKeys.of(100L));
        assertTrue(items.getGroup(GroupKeys.of(100L)).isEmpty());
        data.flush();
        assertEquals(List.of(), rows());
    }

    @Test
    void refusesAnUpdateOfAnotherEntityAtTheSameMapKey() {
        items.insert(new Item(1, 100, 0, 5001, 1));
        assertThrows(IllegalStateException.class, () -> items.update(new Item(2, 100, 0, 5001, 1)));
        assertThrows(IllegalStateException.class, () -> items.update(new Item(3, 100, 5, 5001, 1)));
    }

    @Test
    void loadsAGroupOnceForThreadsAskingAtOnce() throws Exception {
        database.put(Item.class, new Item(1, 100, 0, 5001, 1));
        database.loadGate = new CountDownLatch(1);
        List<Future<Map<Integer, Item>>> asked = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) asked.add(threads.submit(() -> items.getGroup(GroupKeys.of(100L))));
            while (database.loads.get() == 0) Thread.onSpinWait();
            Thread.sleep(50);   // the others ask while the first load waits at the gate
            database.loadGate.countDown();
            Item first = asked.getFirst().get().get(0);
            for (Future<Map<Integer, Item>> bag : asked) assertSame(first, bag.get().get(0));
        }
        assertEquals(1, database.loads.get());
    }

    @Test
    void anEvictedGroupComesBackWithItsUnwrittenChanges() {
        database.put(Item.class, new Item(1, 100, 0, 5001, 1));
        database.put(Item.class, new Item(2, 100, 1, 5002, 1));
        Item updated = items.get(GroupKeys.of(100L), 1);
        updated.amount = 5;
        items.update(updated);
        items.delete(items.get(GroupKeys.of(100L), 0));
        Item inserted = new Item(3, 100, 0, 5003, 1);
        items.insert(inserted);
        items.evict(GroupKeys.of(100L));

        Map<Integer, Item> bag = items.getGroup(GroupKeys.of(100L));
        assertSame(inserted, bag.get(0));
        assertSame(updated, bag.get(1));
        data.flush();
        assertEquals(List.of(List.of(2L, 1, 5), List.of(3L, 0, 1)), rows());
    }

    @Test
    void keysGroupsAndEntitiesBySeveralFields() {
        Quest done = new Quest(1, 100, 1, 7, 1);
        done.progress = 10;
        database.put(Quest.class, done);
        database.put(Quest.class, new Quest(2, 100, 1, 7, 2));
        database.put(Quest.class, new Quest(3, 100, 2, 7, 1));
        Map<String, Quest> group = quests.getGroup(GroupKeys.of(100L, 1));
        assertEquals(Set.of("7:1", "7:2"), group.keySet());
        assertEquals(10, quests.get(GroupKeys.of(100L, 1), MapKeys.of(7, 1)).progress);
        quests.insert(new Quest(4, 100, 1, 8, 1));
        assertEquals(3, group.size(), "the view is live");
        assertNull(quests.get(GroupKeys.of(100L, 2), MapKeys.of(7, 2)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void checksKeyTypes() {
        assertThrows(IllegalArgumentException.class, () -> items.getGroup(GroupKeys.of(100)));
        assertThrows(IllegalArgumentException.class, () -> items.getGroup(GroupKeys.of(100L, 1)));
        assertThrows(IllegalArgumentException.class, () -> ((GroupRepository) items).get(GroupKeys.of(100L), 0L));
        assertThrows(IllegalArgumentException.class, () -> MapKeys.of(7));
        assertThrows(IllegalArgumentException.class, () -> MapKeys.of("a:b", 1));
    }
}
