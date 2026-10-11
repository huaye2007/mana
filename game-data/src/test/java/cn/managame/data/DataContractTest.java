package cn.managame.data;

import cn.managame.data.annotation.*;
import cn.managame.data.error.*;
import cn.managame.data.key.GroupKey;
import cn.managame.data.key.MapKeys;
import cn.managame.data.mapper.EntityMapper;
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
        @cn.managame.data.annotation.GroupKey long roleId;
        @MapKey long taskId;
        Task() {}
        Task(long id, long role, long task) { super(id); roleId = role; taskId = task; }
    }
    static final class Tasks extends GroupRepository<Long,Task> {}
    static class Composite extends Player {
        @cn.managame.data.annotation.GroupKey(order=20) int region;
        @cn.managame.data.annotation.GroupKey(order=10) long role;
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
        public void initialize(EntityMeta meta) {}
        public Object load(EntityMeta meta,Object id) {
            loads.incrementAndGet(); if (loadFailure) throw new IllegalStateException("load"); return null;
        }
        public List<?> loadGroup(EntityMeta meta,GroupKey key) {
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
    @Test void groupsRequireExplicitLoadAndReturnSameMap() {
        var mapper = new RecordingMapper(); var task = new Task(20,1,5);
        try (var data = builder(mapper).build()) {
            var tasks = data.repository(Tasks.class);
            assertThrows(DataOperationException.class, () -> tasks.insert(task));
            assertThrows(DataOperationException.class, () -> tasks.deleteGroup(GroupKey.of(1L)));
            assertEquals(0, mapper.groupLoads.get());
            var group = tasks.getGroup(GroupKey.of(1L));
            assertInstanceOf(ConcurrentHashMap.class, group);
            tasks.insert(task); assertSame(task, group.get(5L));
            tasks.deleteGroup(GroupKey.of(1L));
            assertSame(group, tasks.getGroup(GroupKey.of(1L))); assertTrue(group.isEmpty());
            assertEquals(1, mapper.groupLoads.get());
            data.writer.flush(); assertTrue(mapper.events.isEmpty());
        }
    }
    @Test void loadedGroupDeletesByDatabaseId() {
        var mapper = new RecordingMapper(); mapper.group = List.of(new Task(20,1,5),new Task(21,1,6));
        try (var data = builder(mapper).build()) {
            var tasks = data.repository(Tasks.class); tasks.getGroup(GroupKey.of(1L));
            tasks.deleteGroup(GroupKey.of(1L)); data.writer.flush();
            assertEquals(Set.of(20L,21L), new HashSet<>(mapper.batches.getFirst()));
        }
    }
    @Test void duplicateAndForeignLoadedMapKeysFail() {
        var mapper = new RecordingMapper(); mapper.group = List.of(new Task(20,1,5),new Task(21,1,5));
        try (var data = builder(mapper).build()) {
            assertThrows(DataLoadException.class, () -> data.repository(Tasks.class).getGroup(GroupKey.of(1L)));
            mapper.group = List.of(new Task(20,2,5));
            assertThrows(DataLoadException.class, () -> data.repository(Tasks.class).getGroup(GroupKey.of(1L)));
        }
    }
    @Test void compositeKeysUseExplicitOrderAndTypedGroupValues() {
        var mapper = new RecordingMapper();
        try (var data = GameDataBuilder.builder().repositories(mapper, Composites.class).build()) {
            var repository = data.repository(Composites.class);
            var entity = new Composite(); entity.id=1; entity.role=4; entity.region=2; entity.category=3; entity.code="x";
            var group = repository.getGroup(GroupKey.of(4L,2)); repository.insert(entity);
            assertSame(entity, group.get(MapKeys.of(3,"x")));
            assertThrows(IllegalArgumentException.class, () -> repository.getGroup(GroupKey.of(4,2)));
        }
        assertNotEquals(GroupKey.of(1), GroupKey.of(1L));
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
            public void initialize(EntityMeta m) {}
            public Object load(EntityMeta m, Object id) { return mapper.load(m, id); }
            public List<?> loadGroup(EntityMeta m, GroupKey k) { return mapper.loadGroup(m, k); }
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
    @Test void evictedEntityWithUnsavedChangeReloadsFromMemoryNotStorage() throws Exception {
        var mapper = new RecordingMapper(); mapper.saveFailure = true;
        var data = builder(mapper).retryPolicy(f -> true).flushInterval(Duration.ofMillis(10))
                .cacheExpire(Duration.ofMillis(40)).shutdownTimeout(Duration.ZERO).errorHandler(f -> {}).build();
        try {
            var players = data.repository(Players.class); var p = new Player(1);
            players.insert(p);
            Thread.sleep(200);                             // expired while saves keep failing
            assertSame(p, players.get(1L)); assertEquals(0, mapper.loads.get());
        } finally { assertThrows(DataSaveException.class, data::close); }
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

    @Test void explicitFlushReportsHistoricalFailuresAndKeepsLaterBatchesWorking() {
        var mapper = new RecordingMapper(); mapper.saveFailure = true;
        var data = builder(mapper).errorHandler(error -> {}).build();
        data.repository(Players.class).insert(new Player(1));
        assertThrows(DataSaveException.class, data::flush); assertEquals(1, data.stats().failedBatches());
        mapper.saveFailure = false; data.repository(Players.class).insert(new Player(2));
        assertThrows(DataSaveException.class, data::flush); assertEquals(List.of("INSERT"), mapper.events);
        assertThrows(DataSaveException.class, data::close);
        assertThrows(DataOperationException.class, data::flush); assertFalse(data.stats().accepting());
    }
}
