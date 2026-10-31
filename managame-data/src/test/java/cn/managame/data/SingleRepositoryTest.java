package cn.managame.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SingleRepositoryTest {
    private final MemoryMapper database = new MemoryMapper();
    // A flush interval long enough that only flush() writes, so each test decides when.
    private GameData data = start(Duration.ofMinutes(1), Duration.ofMinutes(2));
    private Players players = data.repository(Players.class);

    @AfterEach
    void close() { data.close(); }

    private GameData start(Duration flushInterval, Duration cacheExpire) {
        return GameData.builder().repositories(database, List.of(Players.class))
                .flushInterval(flushInterval).cacheExpire(cacheExpire).build();
    }

    private List<Object> levels() { return database.rows(Player.class).stream().map(row -> row.get("level")).toList(); }

    @Test
    void writesInsertsUpdatesAndDeletes() {
        Player player = new Player(1, "ann");
        player.items.put(1001, 5L);
        players.insert(player);
        data.flush();
        assertEquals(Map.of("id", 1L, "name", "ann", "level", 1, "gold", 0L, "items", Map.of(1001, 5L)), database.rows(Player.class).getFirst());

        player.level = 2;
        players.update(player);
        data.flush();
        assertEquals(List.of(2), levels());

        players.delete(1L);
        data.flush();
        assertEquals(List.of(), database.rows(Player.class));
        assertNull(players.get(1L));
    }

    @Test
    void loadsOnceAndKeepsTheEntityInMemory() {
        Player stored = new Player(7, "bob");
        stored.level = 3;
        database.put(Player.class, stored);
        Player player = players.get(7L);
        assertNotSame(stored, player);
        assertEquals(3, player.level);
        stored.level = 9;
        database.put(Player.class, stored);
        assertSame(player, players.get(7L));
        assertEquals(3, players.get(7L).level);
    }

    @Test
    void remembersIdsThatDoNotExist() {
        assertNull(players.get(5L));
        database.put(Player.class, new Player(5, "eve"));
        assertNull(players.get(5L), "the database is not asked twice");
        assertThrows(IllegalStateException.class, () -> players.update(new Player(5, "eve")));
        players.delete(5L);   // known not to exist: nothing to do
        data.flush();
        assertEquals(1, database.rows(Player.class).size());
    }

    @Test
    void refusesToInsertAnIdInMemory() {
        players.insert(new Player(1, "ann"));
        assertThrows(IllegalStateException.class, () -> players.insert(new Player(1, "ann")));
    }

    @Test
    void writesARowChangedManyTimesOnce() {
        Player player = new Player(1, "ann");
        players.insert(player);
        for (int level = 2; level <= 10; level++) {
            player.level = level;
            players.update(player);
        }
        data.flush();
        assertEquals(List.of(10), levels());

        players.insert(new Player(2, "bob"));
        players.delete(2L);
        data.flush();
        assertEquals(1, database.rows(Player.class).size(), "inserted and deleted before a write: nothing to write");
    }

    @Test
    void replacesARowDeletedAndInsertedAgain() {
        database.put(Player.class, new Player(3, "old"));
        assertEquals("old", players.get(3L).name);
        players.delete(3L);
        players.insert(new Player(3, "new"));
        data.flush();
        assertEquals("new", database.rows(Player.class).getFirst().get("name"));
    }

    @Test
    void createsAndChangesInOneCall() {
        Player created = players.getOrCreate(4L, id -> new Player(id, "dan"));
        assertSame(created, players.getOrCreate(4L, id -> new Player(id, "other")));
        assertTrue(players.update(4L, player -> player.gold += 30));
        assertFalse(players.update(99L, player -> player.gold += 30));
        assertThrows(IllegalArgumentException.class, () -> players.getOrCreate(8L, id -> new Player(9, "x")));
        data.flush();
        assertEquals(30L, database.rows(Player.class).getFirst().get("gold"));
    }

    @Test
    void loadsAnIdOnceForThreadsAskingAtOnce() throws Exception {
        database.put(Player.class, new Player(7, "bob"));
        database.loadGate = new CountDownLatch(1);
        List<Future<Player>> asked = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) asked.add(threads.submit(() -> players.get(7L)));
            while (database.loads.get() == 0) Thread.onSpinWait();
            Thread.sleep(50);   // the others ask while the first load waits at the gate
            database.loadGate.countDown();
            Player first = asked.getFirst().get();
            for (Future<Player> player : asked) assertSame(first, player.get());
        }
        assertEquals(1, database.loads.get());
    }

    @Test
    void keepsAtMostMaxCacheEntries() {
        data.close();
        data = GameData.builder().repositories(database, List.of(Players.class)).maxCacheEntries(2)
                .flushInterval(Duration.ofMinutes(1)).build();
        players = data.repository(Players.class);
        for (long id = 1; id <= 10; id++) database.put(Player.class, new Player(id, "p" + id));
        for (long id = 1; id <= 10; id++) players.get(id);
        players.cleanUp();
        int loaded = database.loads.get();
        for (long id = 1; id <= 10; id++) players.get(id);
        assertTrue(database.loads.get() - loaded >= 8, "at most two of the ten stayed in memory");
    }

    @Test
    void anEvictedEntityComesBackUnwrittenAsTheSameInstance() {
        Player player = new Player(1, "ann");
        players.insert(player);
        players.evict(1L);
        assertSame(player, players.get(1L), "not written yet: the waiting change is the newest state");

        data.flush();
        players.evict(1L);
        Player loaded = players.get(1L);
        assertNotSame(player, loaded);
        assertEquals("ann", loaded.name);
    }

    @Test
    void forgetsEntitiesUnusedForCacheExpire() throws InterruptedException {
        data.close();
        data = start(Duration.ofMillis(20), Duration.ofMillis(100));
        players = data.repository(Players.class);
        Player stored = new Player(6, "fay");
        database.put(Player.class, stored);
        assertEquals(1, players.get(6L).level);
        stored.level = 4;
        database.put(Player.class, stored);
        // Each look uses the entry again, so looks are spaced wider than cacheExpire.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do Thread.sleep(300);
        while (players.get(6L).level != 4 && System.nanoTime() < deadline);
        assertEquals(4, players.get(6L).level);
    }

    @Test
    void closeWritesWhatWaitsAndRefusesChanges() {
        Player player = new Player(1, "ann");
        players.insert(player);
        data.close();
        assertEquals(1, database.rows(Player.class).size());
        assertThrows(IllegalStateException.class, () -> players.update(player));
        assertSame(player, players.get(1L), "reads keep working");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void checksKeyTypes() {
        assertThrows(IllegalArgumentException.class, () -> ((SingleRepository) players).get(1));
    }
}
