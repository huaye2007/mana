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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** D-WRITE-02: different tables are saved at once, one table by one thread, reports never concurrently. */
class ParallelSaveTest {
    static class Player { @Id long id; Player() {} Player(long id) { this.id = id; } }
    static class Item { @Id long id; Item() {} Item(long id) { this.id = id; } }
    static class Mail { @Id long id; Mail() {} Mail(long id) { this.id = id; } }
    static final class Players extends SingleRepository<Long,Player> {}
    static final class Items extends SingleRepository<Long,Item> {}
    static final class Mails extends SingleRepository<Long,Mail> {}

    /** Hands each batch to {@code save}; counts calls and the most batches of one table running at once. */
    static class Storage implements EntityMapper {
        final AtomicInteger attempts = new AtomicInteger(), running = new AtomicInteger(), mostAtOnce = new AtomicInteger(),
                sameTableOverlaps = new AtomicInteger();
        final ConcurrentHashMap<Class<?>, AtomicInteger> perTable = new ConcurrentHashMap<>();
        volatile Saver save = (meta, entities) -> {};
        interface Saver { void save(EntityMeta meta, List<?> entities) throws Exception; }
        public void initialize(EntityMeta meta, SchemaMode schema) {}
        public Object load(EntityMeta meta, Object id) { return null; }
        public List<?> loadGroup(EntityMeta meta, GroupKeys key) { return List.of(); }
        private void save(EntityMeta meta, List<?> entities) {
            attempts.incrementAndGet();
            AtomicInteger table = perTable.computeIfAbsent(meta.entityType(), ignored -> new AtomicInteger());
            if (table.incrementAndGet() > 1) sameTableOverlaps.incrementAndGet();   // asserted by the tests, not here
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            try { save.save(meta, entities); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
            finally { running.decrementAndGet(); table.decrementAndGet(); }
        }
        public void insertBatch(EntityMeta m, List<?> e) { save(m, e); }
        public void updateBatch(EntityMeta m, List<?> e) { save(m, e); }
        public void deleteBatch(EntityMeta m, List<Object> e) { save(m, e); }
        public void deleteInsertBatch(EntityMeta m, List<?> e) { save(m, e); }
    }
    private static GameDataBuilder builder(Storage storage) {
        return GameDataBuilder.builder().repositories(storage, Players.class, Items.class, Mails.class)
                .flushInterval(Duration.ofMinutes(1)).batchSize(5);
    }
    private static void insertAll(GameData data, int each) {
        for (long id = 1; id <= each; id++) {
            data.repository(Players.class).insert(new Player(id));
            data.repository(Items.class).insert(new Item(id));
            data.repository(Mails.class).insert(new Mail(id));
        }
    }

    @Test void tablesAreSavedAtOnceButNeverOneTableByTwoThreads() throws Exception {
        var storage = new Storage(); var arrived = new CountDownLatch(3);
        storage.save = (meta, entities) -> { arrived.countDown(); assertTrue(arrived.await(5, TimeUnit.SECONDS), "tables were saved one after another"); };
        try (var data = builder(storage).saveParallelism(3).build()) {
            insertAll(data, 10);
            data.flush();   // each table's first batch waits for the other two tables to arrive
            assertEquals(3, storage.mostAtOnce.get());
            assertEquals(6, storage.attempts.get());   // two batches of five per table
            assertEquals(0, storage.sameTableOverlaps.get(), "two threads saved one table at once");
        }
    }

    @Test void aParallelismOfOneSavesTablesOneAfterAnother() {
        var storage = new Storage();
        try (var data = builder(storage).saveParallelism(1).build()) {
            insertAll(data, 10);
            data.flush();
            assertEquals(1, storage.mostAtOnce.get());
        }
    }

    @Test void dropsFromParallelTablesReachTheHandlerOneAtATime() {
        var storage = new Storage();
        storage.save = (meta, entities) -> { throw new IllegalStateException("bad row"); };   // not retryable
        var inHandler = new AtomicInteger(); var calls = new AtomicInteger(); var overlapped = new AtomicInteger();
        var data = builder(storage).saveParallelism(3).errorHandler(failure -> {
            if (inHandler.incrementAndGet() > 1) overlapped.incrementAndGet();   // a throw here would only be logged
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            calls.incrementAndGet(); inHandler.decrementAndGet();
        }).build();
        insertAll(data, 4);
        assertThrows(DataSaveException.class, data::flush);
        assertEquals(0, overlapped.get(), "DataErrorHandler was called concurrently");
        assertEquals(12, calls.get());   // every row of every table, each reported once
        assertThrows(DataSaveException.class, data::close);
    }

    @Test void anOutageStopsEveryTableOfThatStorageTogether() {
        var storage = new Storage();
        storage.save = (meta, entities) -> {
            Thread.sleep(20);
            throw new RuntimeException(new SQLTransientConnectionException("Connection is not available"));
        };
        var errors = new CopyOnWriteArrayList<DataFailure>();
        var data = builder(storage).saveParallelism(1).shutdownTimeout(Duration.ZERO).errorHandler(errors::add).build();
        insertAll(data, 10);   // three tables, two batches each
        assertThrows(DataSaveException.class, data::flush);
        // The tables share one storage, so its second failure stops the third table before it starts.
        assertEquals(2, storage.attempts.get());
        assertEquals(30, data.stats().pendingChanges());
        assertThrows(DataSaveException.class, data::close);
        assertEquals(30, errors.size());
    }
}
