package cn.managame.data;

import cn.managame.data.annotation.Id;
import cn.managame.data.error.DataFailure;
import cn.managame.data.error.DataSaveException;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import org.junit.jupiter.api.Test;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * D-WRITE-05 and D-CLOSE-01 while storage is down: every call waits out a timeout before it fails, as a pool
 * does with its database gone, so what matters is how many calls a pass and a close make.
 */
class StorageOutageTest {
    static class Player { @Id long id; Player() {} Player(long id) { this.id = id; } }
    static final class Players extends SingleRepository<Long,Player> {}

    /** Fails every batch holding one of {@code failing} (all of them when null) after {@code delayMillis}. */
    static final class Storage implements EntityMapper {
        final AtomicInteger attempts = new AtomicInteger(), saved = new AtomicInteger();
        volatile Set<Long> failing;
        volatile long delayMillis;
        public void initialize(EntityMeta meta, SchemaMode schema) {}
        public Object load(EntityMeta meta, Object id) { return null; }
        public List<?> loadGroup(EntityMeta meta, GroupKeys key) { return List.of(); }
        private void save(List<?> entities) {
            attempts.incrementAndGet();
            boolean fails = failing == null || entities.stream().anyMatch(e -> failing.contains(((Player) e).id));
            if (!fails) { saved.addAndGet(entities.size()); return; }
            try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new RuntimeException(new SQLTransientConnectionException("Connection is not available, request timed out"));
        }
        public void insertBatch(EntityMeta m, List<?> e) { save(e); }
        public void updateBatch(EntityMeta m, List<?> e) { save(e); }
        public void deleteBatch(EntityMeta m, List<Object> e) { attempts.incrementAndGet(); }
        public void deleteInsertBatch(EntityMeta m, List<?> e) { save(e); }
    }
    private static GameDataBuilder builder(Storage storage, List<DataFailure> errors) {
        return GameDataBuilder.builder().repositories(storage, Players.class).flushInterval(Duration.ofMinutes(1))
                .batchSize(5).errorHandler(errors::add);
    }
    private static void insert(GameData data, int count) {
        var players = data.repository(Players.class);
        for (long id = 1; id <= count; id++) players.insert(new Player(id));
    }

    @Test void aPassStopsOnceStorageFailsTwiceInARowAndKeepsEverythingPending() {
        var storage = new Storage(); var errors = new CopyOnWriteArrayList<DataFailure>();
        var data = builder(storage, errors).shutdownTimeout(Duration.ZERO).build();
        try {
            insert(data, 20);                                   // four batches
            var failure = assertThrows(DataSaveException.class, data::flush);
            assertEquals(2, storage.attempts.get());            // not one wait per batch
            assertEquals(20, data.stats().pendingChanges());
            assertTrue(failure.getMessage().startsWith("20 "), failure.getMessage());
            assertTrue(errors.isEmpty());                       // kept, not dropped
        } finally { assertThrows(DataSaveException.class, data::close); }
    }

    @Test void oneFailingRowDoesNotStopThePass() {
        var storage = new Storage(); storage.failing = Set.of(1L);   // a locked row, not an outage
        var data = builder(storage, new CopyOnWriteArrayList<>()).shutdownTimeout(Duration.ZERO).build();
        try {
            insert(data, 20);
            assertThrows(DataSaveException.class, data::flush);
            assertEquals(4, storage.attempts.get());
            assertEquals(15, storage.saved.get());               // the other batches were saved
            assertEquals(5, data.stats().pendingChanges());
        } finally { assertThrows(DataSaveException.class, data::close); }
    }

    @Test void closeDuringAnOutageEndsWithinItsTimeoutAndHandsOverEveryChange() {
        var storage = new Storage(); storage.delayMillis = 50;
        var errors = new CopyOnWriteArrayList<DataFailure>();
        var data = builder(storage, errors).shutdownTimeout(Duration.ofMillis(300)).build();
        insert(data, 20);
        long started = System.nanoTime();
        assertThrows(DataSaveException.class, data::close);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        // The timeout plus the call in progress at the deadline; splitting each batch into rows would take 1 s more.
        assertTrue(elapsed < 300 + 2 * 50 + 250, "close took " + elapsed + " ms");
        assertTrue(storage.attempts.get() <= 6, storage.attempts.get() + " attempts");
        assertEquals(20, errors.size());                         // every change, with its entity, to dump
        assertTrue(errors.stream().allMatch(f -> f.attempt() == 0 && f.batch().getFirst() instanceof Player));
        assertInstanceOf(SQLTransientConnectionException.class, errors.getFirst().cause().getCause().getCause());
        assertEquals(0, data.stats().pendingChanges());
    }

    @Test void noSaveStartsAfterTheCloseDeadline() {
        var storage = new Storage(); storage.failing = Set.of();     // healthy
        var errors = new CopyOnWriteArrayList<DataFailure>();
        var data = builder(storage, errors).shutdownTimeout(Duration.ZERO).build();
        insert(data, 3);
        assertThrows(DataSaveException.class, data::close);
        assertEquals(0, storage.attempts.get());                 // a zero timeout leaves no time to save in
        assertEquals(3, errors.size());
    }
}
