package cn.managame.core.write;

import cn.managame.core.DataException;
import cn.managame.core.GameData;
import cn.managame.core.metadata.EntityMetadata;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.support.DataTestSupport.*;

@Timeout(20)
class WriterRetirementTest {
    private static WriteOperation<Row> routed(long id) {
        return WriteOperation.insert(METADATA, new Row(id), id, 1L, "log_day");
    }
    private static WriteBehindEngine retiring(Access access, int capacity) {
        return WriteBehindEngine.logs(access, capacity, 1, Duration.ofMillis(1), 0,
                (ops, error) -> fail(error), Duration.ofMillis(10), 2);
    }

    @Test void idlePartitionsReleaseBuffersAndReuseFixedSaveWorkers() {
        var threads = new CopyOnWriteArrayList<Thread>();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                threads.add(Thread.currentThread()); return super.applyBatch(ops);
            }
        };
        try (var engine = retiring(access, 2)) {
            for (int i = 1; i <= 3; i++) {
                engine.submit(routed(i)); engine.flush();
                until(() -> engine.writerCount() == 0);
                assertTrue(threads.stream().allMatch(Thread::isAlive));
            }
            assertEquals(List.of(1L, 2L, 3L), access.saved);
            assertEquals(1, new HashSet<>(threads).size());
        }
        assertTrue(threads.stream().noneMatch(Thread::isAlive));
    }

    @Test void submissionResolvingItsKeyCanRecreateAnIdleWriter() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var access = new Access() {
            public String writerKey(EntityMetadata<?> metadata, String physical) {
                if (calls.incrementAndGet() == 2) { entered.countDown(); await(release); }
                return super.writerKey(metadata, physical);
            }
        };
        try (var engine = retiring(access, 1)) {
            engine.submit(routed(1)); engine.flush();
            var producer = task(() -> engine.submit(routed(2)));
            await(entered);
            try {
                until(() -> engine.writerCount() == 0);
            } finally { release.countDown(); }
            producer.join(); engine.flush();
            until(() -> engine.writerCount() == 0);
            assertEquals(List.of(1L, 2L), access.saved);
        }
    }

    @Test void inFlightBatchFullQueueAndCloseKeepSingleWriterAndAllowStateQueries() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var engineRef = new AtomicReference<WriteBehindEngine>();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().id().equals(1L)) {
                    entered.countDown(); await(release);
                    engineRef.get().state(Row.class);
                }
                return super.applyBatch(ops);
            }
        };
        try (var engine = retiring(access, 1)) {
            engineRef.set(engine);
            engine.submit(routed(1)); await(entered); engine.submit(routed(2));
            var producer = task(() -> engine.submit(routed(3)));
            until(() -> producer.thread.getState() == Thread.State.WAITING);
            try {
                Thread.sleep(250);
                assertEquals(1, engine.writerCount());
                var closer = task(engine::close);
                until(() -> closer.thread.getState() == Thread.State.WAITING);
                release.countDown(); producer.join(); closer.join();
                assertEquals(List.of(1L, 2L, 3L), access.saved);
                assertEquals(WriterState.CLOSED, engine.state(Row.class));
            } finally { release.countDown(); }
        }
    }

    @Test void concurrentProducersAcrossRecreationNeverOverlapBackendCallsOrLoseWrites() {
        var active = new AtomicInteger(); var maxActive = new AtomicInteger();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                int count = active.incrementAndGet(); maxActive.accumulateAndGet(count, Math::max);
                try { return super.applyBatch(ops); } finally { active.decrementAndGet(); }
            }
        };
        try (var engine = retiring(access, 4)) {
            for (int round = 0; round < 8; round++) {
                long base = round * 100L;
                List<Task> producers = new ArrayList<>();
                for (int p = 0; p < 4; p++) {
                    long first = base + p * 25L;
                    producers.add(task(() -> { for (long id = first; id < first + 25; id++) engine.submit(routed(id)); }));
                }
                producers.forEach(Task::join);
                engine.flush();
                until(() -> engine.writerCount() == 0);
            }
            assertEquals(800, access.saved.size());
            assertEquals(800, new HashSet<>(access.saved).size());
            assertEquals(1, maxActive.get());
        }
    }

    @Test void fixedWritersAndDisabledRetirementArePreserved() throws Exception {
        try (var engine = retiring(new Access(), 2)) {
            engine.submit(insert(1)); engine.submit(routed(2)); engine.flush();
            until(() -> engine.writerCount() == 1);
        }
        try (var engine = new WriteBehindEngine(new Access(), 2, 1, Duration.ofMillis(1), 0,
                (ops, error) -> fail(error), Duration.ZERO)) {
            engine.submit(routed(1)); engine.flush(); Thread.sleep(250);
            assertEquals(1, engine.writerCount());
        }
    }

    @Test void failedBatchesRetireWithoutRetainingPerPartitionFailures() {
        var logged = new AtomicInteger();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) { throw new DataException("offline"); }
        };
        try (var engine = new WriteBehindEngine(access, 2, 1, Duration.ofMillis(1), 0,
                (ops, error) -> logged.incrementAndGet(), Duration.ofMillis(10))) {
            engine.submit(routed(1)); engine.flush(); until(() -> engine.writerCount() == 0);
            assertEquals(1, logged.get());
        }
    }

    @Test void gameDataBuilderAcceptsLogRetirementAndDrainsBothWriterKinds() {
        var access = new Access();
        try (var game = GameData.builder().dataAccess("game", access).ensureSchema(false)
                .writeBatchSize(1).logBatchSize(1).logWriterIdleTimeout(Duration.ofMillis(10)).build()) {
            game.repository(Rows.class).insert(new Row(1));
            game.repository("game", Events.class).append(new Event(2, java.time.LocalDate.of(2026, 9, 1)));
            game.flush();
        }
        assertEquals(Set.of(1L, 2L), new HashSet<>(access.saved));
        assertTrue(access.closed);
        assertThrows(IllegalArgumentException.class, () -> GameData.builder().logWriterIdleTimeout(Duration.ofSeconds(-1)));
    }
}
