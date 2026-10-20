package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.error.DataOperationException;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** The dirty set is bounded (D-WRITE-04): writers wait for room and are refused rather than kept. */
class DirtySetBoundTest {
    static class Player { @Id long id; int score; Player() {} Player(long id) { this.id = id; } }
    static final class Players extends SingleRepository<Long,Player> {}

    static final class CountingMapper implements EntityMapper {
        final List<Object> saved = new CopyOnWriteArrayList<>();
        volatile Runnable duringSave = () -> {};
        public void initialize(EntityMeta meta,SchemaMode schema) {}
        public Object load(EntityMeta meta, Object id) { return null; }
        public List<?> loadGroup(EntityMeta meta, GroupKeys key) { return List.of(); }
        public void insertBatch(EntityMeta m, List<?> e) { duringSave.run(); saved.addAll(e); }
        public void updateBatch(EntityMeta m, List<?> e) { saved.addAll(e); }
        public void deleteBatch(EntityMeta m, List<Object> e) { saved.addAll(e); }
        public void deleteInsertBatch(EntityMeta m, List<?> e) { saved.addAll(e); }
    }

    private static GameDataBuilder builder(CountingMapper mapper, int bound, Duration wait) {
        return GameDataBuilder.builder().repositories(mapper, Players.class)
                .flushInterval(Duration.ofMinutes(1)).maxPendingChanges(bound).pendingChangeWait(wait);
    }

    /**
     * A full dirty set refuses a new entity and records nothing, while a change to an entity already waiting
     * merges into it and is always accepted. A refusal is not a drop: no Handler sees it and close succeeds.
     */
    @Test @Timeout(60) void aFullDirtySetRefusesNewEntitiesAndStillTakesChangesToWaitingOnes() {
        var mapper = new CountingMapper();
        try (var data = builder(mapper, 2, Duration.ZERO).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1)); players.insert(new Player(2));
            assertEquals(2, data.stats().pendingChanges());

            var refused = new Player(3);
            assertThrows(DataOperationException.class, () -> players.insert(refused));
            assertEquals(2, data.stats().pendingChanges(), "a refused change must not be recorded");
            assertEquals(2, data.stats().singleCacheEntries(), "a refused change must not touch the cache");
            assertEquals(1, data.stats().rejectedChanges());
            assertEquals(0, data.stats().failedBatches(), "a refusal is not a drop");

            players.update(new Player(2));                  // already waiting: merges, so it is always accepted
            assertEquals(2, data.stats().pendingChanges());

            data.writer.flush();
            assertEquals(2, mapper.saved.size());
            players.insert(refused);                        // the pass made room
            assertEquals(1, data.stats().pendingChanges());
        }
    }

    /** A writer parks while the set is full and goes through once a pass frees a place. */
    @Test @Timeout(60) void aWriterWaitsForRoomAndProceedsWhenAPassMakesIt() throws Exception {
        var mapper = new CountingMapper();
        try (var data = builder(mapper, 1, Duration.ofSeconds(30)).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1));
            var waiting = CompletableFuture.runAsync(() -> players.insert(new Player(2)));
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
            data.writer.flush();
            waiting.get(30, TimeUnit.SECONDS);
            assertEquals(1, data.stats().pendingChanges());
            assertEquals(0, data.stats().rejectedChanges(), "it waited, it was not refused");
            assertEquals(List.of(1L), List.of(((Player) mapper.saved.getFirst()).id));
        }
    }

    /** Closing refuses a waiting writer instead of holding it, so a full set cannot block shutdown. */
    @Test @Timeout(60) void closeRefusesAWaitingWriterInsteadOfHoldingIt() throws Exception {
        var mapper = new CountingMapper();
        var data = builder(mapper, 1, Duration.ofMinutes(5)).build();
        var players = data.repository(Players.class);
        players.insert(new Player(1));
        var waiting = CompletableFuture.runAsync(() -> players.insert(new Player(2)));
        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
        data.close();
        var failed = assertThrows(ExecutionException.class, () -> waiting.get(30, TimeUnit.SECONDS));
        assertInstanceOf(DataOperationException.class, failed.getCause());
        assertEquals(1, mapper.saved.size(), "the change that was accepted is still saved");
        assertFalse(data.stats().accepting());
    }

    /** A change recorded from inside a pass cannot wait for that pass, so a full set refuses it at once. */
    @Test @Timeout(60) void aWriteFromAPersistenceCallbackIsRefusedInsteadOfStallingThePass() {
        var mapper = new CountingMapper();
        var refusals = new ArrayList<Throwable>();
        try (var data = builder(mapper, 1, Duration.ofMinutes(5)).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1));
            var next = new AtomicLong(1);
            mapper.duringSave = () -> {
                for (int i = 0; i < 2; i++) {       // the first fills the set again, the second finds it full
                    try { players.insert(new Player(next.incrementAndGet())); }
                    catch (DataOperationException refused) { refusals.add(refused); }
                }
            };
            long started = System.nanoTime();
            data.writer.flush();                    // a five-minute wait here would be the bug
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30), "the pass waited for itself");
            assertEquals(1, refusals.size());
            assertEquals(1, data.stats().rejectedChanges());
            assertEquals(1, data.stats().pendingChanges());
        }
    }

    /** A wait that ends with no room refuses, and the count of refusals is the signal an operator sees. */
    @Test @Timeout(60) void aWaitThatFindsNoRoomRefusesAndIsCounted() {
        var mapper = new CountingMapper();
        try (var data = builder(mapper, 1, Duration.ofMillis(50)).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1));
            long started = System.nanoTime();
            assertThrows(DataOperationException.class, () -> players.insert(new Player(2)));
            assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(50), "it must wait first");
            assertEquals(1, data.stats().rejectedChanges());
            assertEquals(1, data.stats().pendingChanges());
        }
    }
}
