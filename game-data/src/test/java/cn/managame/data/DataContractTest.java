package cn.managame.data;

import cn.managame.data.annotation.*;
import cn.managame.data.error.*;
import cn.managame.data.key.GroupKeys;
import cn.managame.data.key.MapKeys;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DataContractTest {
    static class Player { @Id long id; int score; Player() {} Player(long id) { this.id = id; } }
    static final class Players extends SingleRepository<Long,Player> {}
    static class Task extends Player {
        @GroupKey long roleId;
        @MapKey long taskId;
        Task() {}
        Task(long id, long role, long task) { super(id); roleId = role; taskId = task; }
    }
    static final class Tasks extends GroupRepository<Long,Task> {}
    static class Composite extends Player {
        @GroupKey(order=20) int region;
        @GroupKey(order=10) long role;
        @MapKey(order=3) String code;
        @MapKey(order=1) int category;
    }
    static final class Composites extends GroupRepository<String,Composite> {}
    static final class WrongKey extends GroupRepository<Long,Composite> {}
    static abstract class Middle<E> extends SingleRepository<Long,E> {}
    static final class Indirect extends Middle<Player> {}
    static final class RecordingMapper implements EntityMapper {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<List<?>> batches = new CopyOnWriteArrayList<>();
        final AtomicInteger loads = new AtomicInteger(), groupLoads = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();
        volatile boolean loadFailure, saveFailure;
        volatile Runnable beforeSave = () -> {};
        List<?> group = List.of();
        public void initialize(EntityMeta meta,SchemaMode schema) {}
        public Object load(EntityMeta meta,Object id) {
            loads.incrementAndGet(); if (loadFailure) throw new IllegalStateException("load"); return null;
        }
        public List<?> loadGroup(EntityMeta meta,GroupKeys key) {
            groupLoads.incrementAndGet(); if (loadFailure) throw new IllegalStateException("group"); return group;
        }
        void save(String op, List<?> data) {
            attempts.incrementAndGet(); beforeSave.run();
            if (saveFailure) throw new IllegalStateException("save");
            events.add(op); batches.add(List.copyOf(data));
        }
        public void insertBatch(EntityMeta m,List<?> e) { save("INSERT",e); }
        public void updateBatch(EntityMeta m,List<?> e) { save("UPDATE",e); }
        public void deleteBatch(EntityMeta m,List<Object> e) { save("DELETE",e); }
        public void deleteInsertBatch(EntityMeta m,List<?> e) { save("DELETE_INSERT",e); }
    }
    private GameDataBuilder builder(RecordingMapper mapper) {
        return GameDataBuilder.builder().repositories(mapper, Players.class, Tasks.class)
                .flushInterval(Duration.ofMinutes(1));
    }
    @Test void negativeCacheDeleteAndInsertNeverSelect() {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).build()) {
            var players = data.repository(Players.class);
            assertNull(players.get(1L)); assertNull(players.get(1L)); assertEquals(1, mapper.loads.get());
            var p = new Player(2);
            players.insert(p); assertSame(p, players.get(2L)); assertEquals(1, mapper.loads.get());
            players.delete(3L); assertNull(players.get(3L)); assertEquals(1, mapper.loads.get());
            players.update(p); players.delete(2L);
            data.writer.flush();
            assertEquals(List.of("DELETE"), mapper.events);
            assertEquals(List.of(3L), mapper.batches.getFirst());
        }
    }
    @Test void loadFailuresAreNotNegativeCached() {
        var mapper = new RecordingMapper(); mapper.loadFailure = true;
        try (var data = builder(mapper).build()) {
            assertThrows(DataLoadException.class, () -> data.repository(Players.class).get(1L));
            mapper.loadFailure = false;
            assertNull(data.repository(Players.class).get(1L));
            assertEquals(2, mapper.loads.get());
        }
    }
    @Test void groupWritesLoadTheGroupAndGetGroupIsAReadOnlyLiveView() {
        var mapper = new RecordingMapper(); var task = new Task(20,1,5);
        try (var data = builder(mapper).build()) {
            var tasks = data.repository(Tasks.class);
            tasks.insert(task);                            // no getGroup first: the write loads the group
            assertEquals(1, mapper.groupLoads.get());
            var group = tasks.getGroup(GroupKeys.of(1L));
            assertSame(task, group.get(5L)); assertSame(group, tasks.getGroup(GroupKeys.of(1L)));
            assertThrows(UnsupportedOperationException.class, () -> group.remove(5L));
            assertThrows(UnsupportedOperationException.class, () -> group.put(6L, new Task(21,1,6)));
            tasks.deleteGroup(GroupKeys.of(1L)); assertTrue(group.isEmpty());
            tasks.deleteGroup(GroupKeys.of(2L));            // loads and deletes nothing
            assertEquals(2, mapper.groupLoads.get());
            data.writer.flush(); assertTrue(mapper.events.isEmpty());
        }
    }
    @Test void groupWritesAreCheckedAgainstTheLoadedGroup() {
        var mapper = new RecordingMapper(); mapper.group = List.of(new Task(20,1,5));
        try (var data = builder(mapper).build()) {
            var tasks = data.repository(Tasks.class);
            assertThrows(DataOperationException.class, () -> tasks.insert(new Task(30,1,5)));   // MapKey taken
            assertThrows(DataOperationException.class, () -> tasks.update(new Task(31,1,6)));   // MapKey missing
            assertThrows(DataOperationException.class, () -> tasks.update(new Task(32,1,5)));   // another @Id
            tasks.delete(new Task(33,1,6));                                                     // absent: no-op
            var replacement = new Task(20,1,5); tasks.update(replacement);
            assertSame(replacement, tasks.get(GroupKeys.of(1L), 5L));
            tasks.delete(new Task(20,1,5));                // deletes whatever holds the MapKey
            assertNull(tasks.get(GroupKeys.of(1L), 5L));
            data.writer.flush();
            assertEquals(List.of("DELETE"), mapper.events); assertEquals(List.of(20L), mapper.batches.getFirst());
        }
    }
    @Test void cachedSingleWritesFailFastAndUncachedOnesNeverSelect() {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).build()) {
            var players = data.repository(Players.class);
            assertNull(players.get(1L));
            assertThrows(DataOperationException.class, () -> players.update(new Player(1)));  // cached as absent
            players.delete(1L);                                                               // absent: no-op
            var p = new Player(1); players.insert(p);
            assertThrows(DataOperationException.class, () -> players.insert(new Player(1)));
            players.update(new Player(9)); players.delete(8L);                                // uncached: recorded as is
            assertEquals(1, mapper.loads.get());
            data.writer.flush();
            assertEquals(List.of("DELETE", "INSERT", "UPDATE"), mapper.events);
        }
    }
    @Test void getOrCreateInsertsOnceAndReturnsTheCachedEntity() {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).build()) {
            var players = data.repository(Players.class); var created = new AtomicInteger();
            var first = players.getOrCreate(1L, id -> { created.incrementAndGet(); return new Player(id); });
            assertSame(first, players.getOrCreate(1L, id -> fail("must not create again")));
            assertSame(first, players.get(1L)); assertEquals(1, created.get());
            assertThrows(DataOperationException.class, () -> players.getOrCreate(2L, id -> new Player(3)));
            assertNull(players.get(2L));
            data.writer.flush();
            assertEquals(List.of("INSERT"), mapper.events); assertEquals(List.of(first), mapper.batches.getFirst());
        }
    }
    @Test void loadedGroupDeletesByDatabaseId() {
        var mapper = new RecordingMapper(); mapper.group = List.of(new Task(20,1,5),new Task(21,1,6));
        try (var data = builder(mapper).build()) {
            var tasks = data.repository(Tasks.class); tasks.getGroup(GroupKeys.of(1L));
            tasks.deleteGroup(GroupKeys.of(1L)); data.writer.flush();
            assertEquals(Set.of(20L,21L), new HashSet<>(mapper.batches.getFirst()));
        }
    }
    @Test void duplicateAndForeignLoadedMapKeysFail() {
        var mapper = new RecordingMapper(); mapper.group = List.of(new Task(20,1,5),new Task(21,1,5));
        try (var data = builder(mapper).build()) {
            assertThrows(DataLoadException.class, () -> data.repository(Tasks.class).getGroup(GroupKeys.of(1L)));
            mapper.group = List.of(new Task(20,2,5));
            assertThrows(DataLoadException.class, () -> data.repository(Tasks.class).getGroup(GroupKeys.of(1L)));
        }
    }
    @Test void compositeKeysUseExplicitOrderAndTypedGroupValues() {
        var mapper = new RecordingMapper();
        try (var data = GameDataBuilder.builder().repositories(mapper, Composites.class).build()) {
            var repository = data.repository(Composites.class);
            var entity = new Composite(); entity.id=1; entity.role=4; entity.region=2; entity.category=3; entity.code="x";
            var group = repository.getGroup(GroupKeys.of(4L,2)); repository.insert(entity);
            assertSame(entity, group.get(MapKeys.of(3,"x")));
            assertThrows(IllegalArgumentException.class, () -> repository.getGroup(GroupKeys.of(4,2)));
        }
        assertNotEquals(GroupKeys.of(1), GroupKeys.of(1L));
        assertThrows(IllegalArgumentException.class, () -> MapKeys.of("a:b","c"));
        assertThrows(IllegalArgumentException.class, () -> GameDataBuilder.builder().repositories(mapper, WrongKey.class).build());
        assertThrows(IllegalArgumentException.class, () -> GameDataBuilder.builder().repositories(mapper, Indirect.class).build());
    }
    @ParameterizedTest
    @CsvSource({"INSERT,UPDATE,INSERT","INSERT,DELETE,NONE","UPDATE,UPDATE,UPDATE","UPDATE,DELETE,DELETE",
            "DELETE,INSERT,DELETE_INSERT","DELETE,DELETE,DELETE","DELETE_INSERT,UPDATE,DELETE_INSERT","DELETE_INSERT,DELETE,DELETE"})
    void legalMergeTable(String old, String next, String expected) {
        var entity = new Player(1);
        var result = PendingBuffer.merge(new PendingBuffer.Change(DataOperation.valueOf(old), null),DataOperation.valueOf(next),entity);
        if (expected.equals("NONE")) assertNull(result);
        else assertEquals(DataOperation.valueOf(expected),result.operation());
    }
    @ParameterizedTest @CsvSource({"INSERT,INSERT","UPDATE,INSERT","DELETE,UPDATE","DELETE_INSERT,INSERT"})
    void illegalMergeTable(String old, String next) {
        assertThrows(DataOperationException.class, () -> PendingBuffer.merge(
                new PendingBuffer.Change(DataOperation.valueOf(old),null),DataOperation.valueOf(next),new Player(1)));
    }
    @Test void illegalInsertDoesNotReplaceCachedEntity() {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).build()) {
            var players = data.repository(Players.class);
            var original = new Player(1); players.insert(original);
            assertThrows(DataOperationException.class, () -> players.insert(new Player(1)));
            assertSame(original,players.get(1L));
        }
    }
    @Test void batchesOrderedAndSplitAndNeverMergeAcrossFlushes() {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).batchSize(2).build()) {
            var p = data.repository(Players.class);
            p.delete(10L); p.delete(11L); p.insert(new Player(11));
            p.insert(new Player(12)); p.insert(new Player(13)); p.insert(new Player(14)); p.update(new Player(15));
            data.writer.flush();
            assertEquals(List.of("DELETE","DELETE_INSERT","INSERT","INSERT","UPDATE"),mapper.events);
            assertTrue(mapper.batches.stream().allMatch(b -> b.size() <= 2));
            p.delete(12L); data.writer.flush();
            assertEquals("DELETE",mapper.events.getLast());
        }
    }
    @Test void finalFailureAndHandlerFailureDoNotStopLaterBatchesAndCloseReportsIt() {
        var mapper = new RecordingMapper(); var errors = new ArrayList<DataFailure>();
        var data = builder(mapper).batchSize(1).errorHandler(f -> {
            errors.add(f); mapper.saveFailure=false; throw new IllegalStateException("handler");
        }).build();
        data.repository(Players.class).insert(new Player(1)); data.repository(Players.class).insert(new Player(2));
        mapper.saveFailure=true; data.writer.flush();
        assertEquals(1,errors.size()); assertEquals(1,mapper.events.size());
        assertEquals(1,errors.getFirst().attempt());
        assertThrows(DataSaveException.class,data::close);
        assertThrows(DataOperationException.class, () -> data.repository(Players.class).update(new Player(3)));
        assertThrows(DataSaveException.class,data::close);
    }
    @Test void retryableFailureKeepsChangesUntilStorageRecovers() {
        var mapper = new RecordingMapper(); mapper.saveFailure = true;
        var errors = new ArrayList<DataFailure>();
        try (var data = builder(mapper).retryPolicy(f -> true).errorHandler(errors::add).build()) {
            var players = data.repository(Players.class); var p = new Player(1);
            players.insert(p);
            assertThrows(DataSaveException.class, data::flush);
            assertEquals(1, data.stats().pendingChanges());
            p.score = 7; players.update(p);              // merges with the kept INSERT
            mapper.saveFailure = false; data.flush();
            assertEquals(List.of("INSERT"), mapper.events); assertSame(p, mapper.batches.getFirst().getFirst());
            assertEquals(0, data.stats().pendingChanges()); assertTrue(errors.isEmpty());
        }
    }
    @Test void defaultPolicyKeepsOnlyTransientFailures() {
        assertTrue(RetryPolicy.isTransient(new RuntimeException(new java.sql.SQLTransientConnectionException("pool"))));
        assertTrue(RetryPolicy.isTransient(new java.sql.SQLException("deadlock", "40001", 1213)));
        assertTrue(RetryPolicy.isTransient(new java.sql.SQLException("lost", "08S01")));
        assertFalse(RetryPolicy.isTransient(new java.sql.SQLIntegrityConstraintViolationException("dup", "23000", 1062)));
        assertFalse(RetryPolicy.isTransient(new java.sql.SQLException("too long", "22001", 1406)));
        assertFalse(RetryPolicy.isTransient(new IllegalStateException("codec")));
    }
    @Test void nonRetryableBadRowIsIsolatedFromItsBatch() {
        var mapper = new RecordingMapper(); var errors = new ArrayList<DataFailure>();
        var bad = new Player(2);
        mapper.beforeSave = () -> {};
        EntityMapper failing = new EntityMapper() {
            public void initialize(EntityMeta m,SchemaMode schema) {}
            public Object load(EntityMeta m, Object id) { return mapper.load(m, id); }
            public List<?> loadGroup(EntityMeta m, GroupKeys k) { return mapper.loadGroup(m, k); }
            public void insertBatch(EntityMeta m, List<?> e) {
                if (e.contains(bad)) throw new IllegalArgumentException("value too long");
                mapper.insertBatch(m, e);
            }
            public void updateBatch(EntityMeta m, List<?> e) { mapper.updateBatch(m, e); }
            public void deleteBatch(EntityMeta m, List<Object> e) { mapper.deleteBatch(m, e); }
            public void deleteInsertBatch(EntityMeta m, List<?> e) { mapper.deleteInsertBatch(m, e); }
        };
        try (var data = GameDataBuilder.builder().repositories(failing, Players.class).flushInterval(Duration.ofMinutes(1))
                .errorHandler(errors::add).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1)); players.insert(bad); players.insert(new Player(3));
            assertThrows(DataSaveException.class, data::flush);
            assertEquals(2, mapper.batches.size());
            assertTrue(mapper.batches.stream().allMatch(b -> b.size() == 1 && b.getFirst() != bad));
            assertEquals(1, errors.size()); assertEquals(List.of(bad), errors.getFirst().batch());
            assertEquals(2, errors.getFirst().attempt()); assertEquals(0, data.stats().pendingChanges());
        } catch (DataSaveException expectedOnClose) { /* historical failure is reported again by close */ }
    }
    @Test void entitiesWithUnsavedChangesAreNeverEvicted() throws Exception {
        var mapper = new RecordingMapper(); mapper.saveFailure = true;
        var data = builder(mapper).retryPolicy(f -> true).flushInterval(Duration.ofMillis(10))
                .cacheExpire(Duration.ofMillis(40)).shutdownTimeout(Duration.ZERO).errorHandler(f -> {}).build();
        try {
            var players = data.repository(Players.class); var p = new Player(1);
            var tasks = data.repository(Tasks.class); tasks.getGroup(GroupKeys.of(7L));
            var task = new Task(5, 7, 1); tasks.insert(task);
            players.insert(p);
            Thread.sleep(200);                             // idle far beyond cacheExpire while saves keep failing
            assertSame(p, players.get(1L)); assertEquals(0, mapper.loads.get());
            assertSame(task, tasks.get(GroupKeys.of(7L), 1L)); assertEquals(1, mapper.groupLoads.get());
        } finally { assertThrows(DataSaveException.class, data::close); }
    }
    @Test void cleanIdleEntriesAreEvictedAndReloaded() throws Exception {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).flushInterval(Duration.ofMillis(10)).cacheExpire(Duration.ofMillis(40)).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1));
            assertNull(data.repository(Tasks.class).getGroup(GroupKeys.of(7L)).get(1L));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((data.stats().singleCacheEntries() != 0 || data.stats().groupCacheEntries() != 0) && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(0, data.stats().singleCacheEntries()); assertEquals(0, data.stats().groupCacheEntries());
            assertEquals(List.of("INSERT"), mapper.events);  // saved before it became evictable
            assertNull(players.get(1L)); assertEquals(1, mapper.loads.get()); // reloaded from storage
        }
    }
    @Test void closeRetriesThenDropsAndReportsLeftovers() {
        var mapper = new RecordingMapper(); mapper.saveFailure = true;
        var errors = new CopyOnWriteArrayList<DataFailure>();
        var data = builder(mapper).retryPolicy(f -> true).shutdownTimeout(Duration.ofMillis(50)).errorHandler(errors::add).build();
        var p = new Player(1); data.repository(Players.class).insert(p);
        assertThrows(DataSaveException.class, data::close);
        assertEquals(1, errors.size()); assertEquals(List.of(p), errors.getFirst().batch());
        assertTrue(mapper.attempts.get() >= 2);
    }
    @Test void closeWaitsForActivePipelineAndFlushesNextBuffer() throws Exception {
        var mapper = new RecordingMapper();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        mapper.beforeSave=() -> { if (once.compareAndSet(false,true)) { entered.countDown(); await(release); } };
        var data = builder(mapper).build(); var players = data.repository(Players.class);
        players.insert(new Player(1));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(data.writer::flush);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            players.insert(new Player(2));
            Future<?> close = executor.submit(data::close);
            assertThrows(TimeoutException.class, () -> close.get(50,TimeUnit.MILLISECONDS));
            release.countDown(); first.get(3,TimeUnit.SECONDS); close.get(3,TimeUnit.SECONDS);
        } finally { release.countDown(); data.close(); }
        assertEquals(List.of("INSERT","INSERT"),mapper.events);
        assertThrows(DataOperationException.class, () -> players.insert(new Player(3)));
    }
    @Test void closeFromCallbackIsRejectedWithoutDeadlock() {
        var mapper = new RecordingMapper(); var data = builder(mapper).build();
        mapper.beforeSave=() -> assertThrows(DataOperationException.class,data::close);
        data.repository(Players.class).insert(new Player(1)); data.close();
    }
    @Test void backgroundFlushRunsWithoutExplicitFlush() throws Exception {
        var mapper = new RecordingMapper(); var saved = new CountDownLatch(1);
        mapper.beforeSave=saved::countDown;
        try (var data = builder(mapper).flushInterval(Duration.ofMillis(20)).build()) {
            data.repository(Players.class).insert(new Player(1));
            assertTrue(saved.await(3,TimeUnit.SECONDS));
        }
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(3,TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }

    @Test void explicitFlushSavesEverythingAcceptedBeforeItAndKeepsLaterChangesPending() {
        var mapper = new RecordingMapper();
        try (var data = builder(mapper).build()) {
            var players = data.repository(Players.class);
            players.insert(new Player(1));
            var once = new AtomicBoolean();
            mapper.beforeSave = () -> {
                assertThrows(DataOperationException.class, data::flush);
                if (once.compareAndSet(false, true)) players.insert(new Player(2)); // recorded after the claim
            };
            assertEquals(1, data.stats().pendingChanges()); data.flush();
            assertEquals(List.of("INSERT"), mapper.events); assertEquals(1, data.stats().pendingChanges());
            data.flush();
            assertEquals(List.of("INSERT", "INSERT"), mapper.events);
            assertEquals(0, data.stats().pendingChanges()); assertEquals(2, data.stats().singleCacheEntries());
            assertEquals(2, data.stats().flushes()); assertEquals(0, data.stats().failedBatches());
            assertTrue(data.stats().saveNanos() > 0);
        }
    }

    @Test void explicitFlushReportsOnlyItsOwnFailuresWhileCloseReportsTheLifetime() {
        var mapper = new RecordingMapper(); mapper.saveFailure = true;
        var data = builder(mapper).errorHandler(error -> {}).build();
        data.repository(Players.class).insert(new Player(1));
        assertThrows(DataSaveException.class, data::flush); assertEquals(1, data.stats().failedBatches());
        mapper.saveFailure = false; data.repository(Players.class).insert(new Player(2));
        data.flush();                                  // the earlier drop does not fail a clean flush
        assertEquals(List.of("INSERT"), mapper.events);
        assertThrows(DataSaveException.class, data::close);
        assertThrows(DataOperationException.class, data::flush); assertFalse(data.stats().accepting());
    }
    /** A mapper whose loads of key 1 block until released, counting loads per key. */
    static final class BlockingLoads implements EntityMapper {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final ConcurrentHashMap<Object, AtomicInteger> loads = new ConcurrentHashMap<>();
        public void initialize(EntityMeta meta,SchemaMode schema) {}
        public Object load(EntityMeta meta, Object id) {
            loads.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            if (Long.valueOf(1).equals(id)) { entered.countDown(); await(release); return new Player(1); }
            return null;
        }
        public List<?> loadGroup(EntityMeta meta, GroupKeys key) { return List.of(); }
        public void insertBatch(EntityMeta m, List<?> e) {}
        public void updateBatch(EntityMeta m, List<?> e) {}
        public void deleteBatch(EntityMeta m, List<Object> e) {}
        public void deleteInsertBatch(EntityMeta m, List<?> e) {}
    }

    @Test void concurrentLoadsOfOneKeyShareOneLoadAndDoNotBlockOtherKeys() throws Exception {
        var mapper = new BlockingLoads();
        try (var data = GameDataBuilder.builder().repositories(mapper, Players.class).flushInterval(Duration.ofMinutes(1)).build();
             var threads = Executors.newFixedThreadPool(2)) {
            var players = data.repository(Players.class);
            Future<Player> first = threads.submit(() -> players.get(1L));
            assertTrue(mapper.entered.await(3, TimeUnit.SECONDS));
            Future<Player> second = threads.submit(() -> players.get(1L));
            assertNull(players.get(2L));                 // another key is not blocked by the running load
            mapper.release.countDown();
            assertSame(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, mapper.loads.get(1L).get());
        }
    }

    @Test void aMutationDuringTheFirstLoadWinsOverTheLoadedValue() throws Exception {
        var mapper = new BlockingLoads();
        try (var data = GameDataBuilder.builder().repositories(mapper, Players.class).flushInterval(Duration.ofMinutes(1)).build();
             var threads = Executors.newSingleThreadExecutor()) {
            var players = data.repository(Players.class);
            Future<Player> loading = threads.submit(() -> players.get(1L));
            assertTrue(mapper.entered.await(3, TimeUnit.SECONDS));
            var updated = new Player(1); updated.score = 9;
            var mutation = CompletableFuture.runAsync(() -> players.update(updated)); // waits for the load
            mapper.release.countDown();
            mutation.get(3, TimeUnit.SECONDS);
            loading.get(3, TimeUnit.SECONDS);
            assertSame(updated, players.get(1L));
            assertEquals(1, mapper.loads.get(1L).get());
        }
    }
}
