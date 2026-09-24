package cn.managame.core.write;

import cn.managame.core.DataException;
import cn.managame.core.GameData;
import cn.managame.core.metadata.EntityMetadata;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DoubleBufferSaveTest {
    record Saved(WriteType type, long id, int value, String table) { }
    static class RecordingAccess extends Access {
        final List<Saved> writes = new CopyOnWriteArrayList<>();
        @Override public BatchResult applyBatch(List<WriteOperation<?>> operations) {
            for (var op : operations) writes.add(new Saved(op.type(), op.id() == null ? -2 : (Long) op.id(),
                    op.entity() == null ? 0 : ((Row) op.entity()).value, op.physicalName()));
            return super.applyBatch(operations);
        }
    }
    private static WriteOperation<Row> update(long id, int value) {
        var row = new Row(id); row.value = value;
        return WriteOperation.update(METADATA, row, id, 1L);
    }
    private static WriteOperation<Row> routed(String table, long id) {
        return WriteOperation.insert(METADATA, new Row(id), id, 1L, table);
    }

    @Test void fullReceivingBufferMergesUpdatesAndReadsTheLatestLiveEntityAtSaveTime() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var access = new RecordingAccess() {
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().id().equals(-1L)) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        try (var engine = engine(access, 1, 1, Duration.ofMillis(1), (ops, error) -> fail(error))) {
            try {
                engine.submit(insert(-1)); await(entered);
                engine.submit(insert(1));
                for (int i = 0; i < 1000; i++) engine.submit(update(1, i));
                var latest = update(1, 1000);
                engine.submit(latest);
                latest.entity().value = 2000;
            } finally { release.countDown(); }
            engine.flush();
            assertEquals(List.of(new Saved(WriteType.INSERT, -1, 0, null),
                    new Saved(WriteType.INSERT, 1, 2000, null)), access.writes);
        }
    }

    @Test void cancelledInsertNeverReachesDatabaseAndDoesNotHoldCapacityOrFlush() {
        var access = new RecordingAccess();
        try (var engine = engine(access, 1, 10, Duration.ofDays(1), (ops, error) -> fail(error))) {
            engine.submit(insert(1));
            engine.submit(WriteOperation.delete(METADATA, 1L, 1L));
            engine.submit(insert(2));
            engine.flush();
            assertEquals(List.of(new Saved(WriteType.INSERT, 2, 0, null)), access.writes);
        }
    }

    @Test void replacementIsOrderedAndGroupDeletionRemainsABarrierAcrossSmallBatches() {
        var access = new RecordingAccess();
        try (var engine = engine(access, 20, 10, Duration.ofDays(1), (ops, error) -> fail(error))) {
            engine.submit(WriteOperation.delete(METADATA, 1L, 1L));
            engine.submit(insert(1)); engine.submit(update(1, 7));
            engine.submit(WriteOperation.deleteGroup(METADATA, 1L));
            engine.submit(insert(1));
            engine.flush();
            assertEquals(List.of(new Saved(WriteType.DELETE, 1, 0, null),
                    new Saved(WriteType.INSERT, 1, 7, null),
                    new Saved(WriteType.DELETE_GROUP, -2, 0, null),
                    new Saved(WriteType.INSERT, 1, 0, null)), access.writes);
        }
    }

    @Test void sizeOneBackendSkipsReplacementInsertIfDeleteFails() {
        var failures = new AtomicInteger();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var access = new RecordingAccess() {
            @Override public int maxBatchSize() { return 1; }
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                assertEquals(1, ops.size());
                if (ops.getFirst().id().equals(-1L)) { entered.countDown(); await(release); }
                if (ops.getFirst().type() == WriteType.DELETE) throw new DataException("cannot delete");
                return super.applyBatch(ops);
            }
        };
        try (var engine = engine(access, 10, 10, Duration.ofMillis(1), (ops, error) -> failures.incrementAndGet())) {
            try {
                engine.submit(insert(-1)); await(entered);
                engine.submit(WriteOperation.delete(METADATA, 1L, 1L));
                engine.submit(insert(1));
            } finally { release.countDown(); }
            engine.flush();
            assertEquals(List.of(-1L), access.saved);
            assertEquals(2, failures.get()); // failed delete and explicitly skipped insert
        }
    }

    @Test void anInFlightInsertCannotBeCancelledByTheNextBuffer() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var access = new RecordingAccess() {
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().type() == WriteType.INSERT) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        try (var engine = engine(access, 1, 1, Duration.ofMillis(1), (ops, error) -> fail(error))) {
            try {
                engine.submit(insert(1)); await(entered);
                engine.submit(WriteOperation.delete(METADATA, 1L, 1L));
            } finally { release.countDown(); }
            engine.flush();
            assertEquals(List.of(WriteType.INSERT, WriteType.DELETE), access.writes.stream().map(Saved::type).toList());
        }
    }

    @Test void fixedWorkersPreserveTableAffinityAndOtherShardsProgressDuringBlockedIo() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var threads = new ConcurrentHashMap<String, Set<Thread>>();
        var access = new RecordingAccess() {
            @Override public String writerKey(EntityMetadata<?> metadata, String table) { return table; }
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                String table = ops.getFirst().physicalName();
                threads.computeIfAbsent(table, ignored -> ConcurrentHashMap.newKeySet()).add(Thread.currentThread());
                if (table.equals("A") && ops.getFirst().id().equals(1L)) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        try (var engine = new WriteBehindEngine(access, 10, 1, Duration.ofMillis(1), 0,
                (ops, error) -> fail(error), Duration.ZERO, 2)) {
            try {
                engine.submit(routed("A", 1)); await(entered);
                engine.submit(routed("C", 1)); // A and C share one shard
                engine.submit(routed("B", 1)); // B belongs to the other shard
                until(() -> access.writes.stream().anyMatch(write -> "B".equals(write.table())));
                assertFalse(threads.containsKey("C"));
                engine.submit(routed("A", 2));
            } finally { release.countDown(); }
            engine.flush();
            assertEquals(4, access.writes.size());
            assertEquals(threads.get("A"), threads.get("C"));
            assertNotEquals(threads.get("A"), threads.get("B"));
            assertTrue(threads.values().stream().allMatch(set -> set.size() == 1));
        }
        assertTrue(threads.values().stream().flatMap(Set::stream).noneMatch(Thread::isAlive));
    }

    @Test void logQueuesKeepDuplicateIdsAndDrainInFifoOrderWithoutMerging() {
        var access = new RecordingAccess();
        try (var game = GameData.builder().dataAccess("game", access).ensureSchema(false)
                .writeThreads(1).logWriteThreads(2).logBatchSize(10).logFlushInterval(Duration.ofDays(1)).build()) {
            var logs = game.repository(Logs.class);
            for (int i = 0; i < 5; i++) { var row = new Row(1); row.value = i; logs.append(row); }
            logs.flush();
            assertEquals(List.of(0, 1, 2, 3, 4), access.writes.stream().map(Saved::value).toList());
        }
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().writeThreads(0));
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().logWriteThreads(0));
    }
    @Test void interruptedSaveWorkerRejectsNewTablesInsteadOfLeavingTheirFlushBlocked() {
        var access = new Access() {
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                Thread.currentThread().interrupt();
                return super.applyBatch(ops);
            }
        };
        var engine = new WriteBehindEngine(access, 10, 1, Duration.ofMillis(1), 0,
                (ops, error) -> fail(error), Duration.ZERO, 1);
        try {
            engine.submit(routed("A", 1));
            until(() -> engine.state(Row.class) == WriterState.FAILED);
            assertThrows(DataException.class, () -> engine.submit(routed("B", 2)));
            assertThrows(DataException.class, engine::flush);
        } finally { assertThrows(DataException.class, engine::close); }
    }

    @Test void boundedLogQueueDrainsAnAdmittedWaitingProducerDuringClose() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var access = new RecordingAccess() {
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().id().equals(-1L)) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        try (var engine = WriteBehindEngine.logs(access, 1, 1, Duration.ofMillis(1), 0,
                (ops, error) -> fail(error), Duration.ZERO, 1)) {
            try {
                engine.submit(insert(-1)); await(entered); engine.submit(insert(1));
                var producer = task(() -> engine.submit(insert(1)));
                until(() -> producer.thread.getState() == Thread.State.WAITING);
                var closer = task(engine::close);
                until(() -> closer.thread.getState() == Thread.State.WAITING);
                release.countDown(); producer.join(); closer.join();
                assertEquals(List.of(-1L, 1L, 1L), access.saved);
            } finally { release.countDown(); }
        }
    }
}