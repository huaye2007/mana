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

@Timeout(15)
class WriteBehindEngineTest {
    @Test void closeDrainsBatchAlreadyTakenFromQueue() {
        var access = new Access();
        var writer = engine(access, 10, 10, Duration.ofMinutes(1), (ops, error) -> fail(error));
        writer.submit(insert(1));
        until(() -> writer.metrics(Row.class).queueSize() == 0);
        assertEquals(1, writer.metrics(Row.class).pendingCount());
        writer.close();
        assertEquals(List.of(1L), access.saved);
        assertEquals(0, writer.metrics(Row.class).pendingCount());
        assertEquals(TableWriterState.CLOSED, writer.state(Row.class));
        assertThrows(IllegalStateException.class, () -> writer.submit(insert(2)));
    }

    @Test void closeRejectsSubmissionStillResolvingItsWriterWithoutLateRegistration() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var access = new Access() {
            public String writerKey(EntityMetadata<?> m, String physical) {
                entered.countDown(); await(release); return "late-table";
            }
        };
        var writer = engine(access, 1, 1, Duration.ofMillis(1), (ops, error) -> fail(error));
        var producer = task(() -> assertThrows(IllegalStateException.class, () -> writer.submit(insert(1))));
        await(entered);
        try {
            var closer = task(writer::close);
            closer.join();
            assertEquals(0, writer.tableWriterCount());
        } finally { release.countDown(); }
        producer.join();
        assertTrue(access.saved.isEmpty());
        assertEquals(0, writer.tableWriterCount());
        assertEquals(TableWriterState.CLOSED, writer.state(Row.class));
    }

    @Test void fullQueueAndInterruptedCloseStillDrainWithoutInterruptingDatabase() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().id().equals(1L)) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        var writer = engine(access, 1, 1, Duration.ofMillis(1), (ops, error) -> fail(error));
        writer.submit(insert(1)); await(entered); writer.submit(insert(2));
        var producer = task(() -> writer.submit(insert(3)));
        until(() -> writer.metrics(Row.class).pendingCount() == 3);
        var interruptedAtReturn = new AtomicBoolean();
        var closer = task(() -> { writer.close(); interruptedAtReturn.set(Thread.currentThread().isInterrupted()); });
        closer.thread.interrupt();
        var secondCloser = task(writer::close);
        release.countDown(); producer.join(); closer.join(); secondCloser.join();
        assertTrue(interruptedAtReturn.get());
        assertEquals(List.of(1L, 2L, 3L), access.saved);
        assertEquals(0, writer.metrics(Row.class).pendingCount());
    }

    @Test void producersFillOtherBufferWhileDatabaseIsBlockedAndDrainInOrder() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var sizes = new ArrayList<Integer>();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                sizes.add(ops.size());
                if (ops.getFirst().id().equals(-1L)) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        var writer = engine(access, 1000, 17, Duration.ofMillis(1), (ops, error) -> fail(error));
        try {
            writer.submit(insert(-1)); await(entered);
            var producers = new ArrayList<Task>();
            for (int producer = 0; producer < 4; producer++) {
                long offset = producer * 200L;
                producers.add(task(() -> { for (int i = 0; i < 200; i++) writer.submit(insert(offset + i)); }));
            }
            for (var producer : producers) producer.join();
            assertEquals(800, writer.metrics(Row.class).queueSize());
            assertEquals(801, writer.metrics(Row.class).pendingCount());
            release.countDown(); writer.close();
            assertEquals(801, access.saved.size());
            assertEquals(801, new HashSet<>(access.saved).size());
            assertTrue(sizes.stream().allMatch(size -> size <= 17));
            for (int producer = 0; producer < 4; producer++) {
                long start = producer * 200L;
                assertEquals(java.util.stream.LongStream.range(start, start + 200).boxed().toList(),
                        access.saved.stream().filter(id -> id >= start && id < start + 200).toList());
            }
        } finally { release.countDown(); writer.close(); }
    }

    @Test void failureIsLoggedOnceAndDoesNotAccumulateOrEscapeFlushAndClose() {
        var failures = new AtomicInteger();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if ((long) ops.getFirst().id() < 100) throw new DataException("database unavailable");
                return super.applyBatch(ops);
            }
        };
        var writer = engine(access, 8, 1, Duration.ofMillis(1), (ops, error) -> failures.incrementAndGet());
        for (long i = 0; i <= 100; i++) writer.submit(insert(i));
        assertDoesNotThrow(() -> { writer.flush(); });
        assertEquals(100, failures.get());
        assertEquals(100, writer.metrics(Row.class).failedBatches());
        assertEquals(List.of(100L), access.saved);
        assertDoesNotThrow(writer::close);
    }

    @Test void partialFailureOnlyLogsUnsuccessfulOperationsAndContinues() {
        var logged = new ArrayList<Long>();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                return BatchResult.of(ops.size(), List.of(new BatchItemResult(1, BatchItemState.FAILED, "bad row")));
            }
        };
        var writer = engine(access, 8, 3, Duration.ofSeconds(1), (ops, error) ->
                ops.forEach(op -> logged.add((Long) op.id())));
        writer.submit(insert(1)); writer.submit(insert(2)); writer.submit(insert(3));
        writer.flush();
        assertEquals(List.of(2L), logged);
        assertEquals(2, writer.metrics(Row.class).successfulOperations());
        writer.close();
    }

    @Test void closeDoesNotCloseDataAccessUntilTheBatchFinishes() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                entered.countDown(); await(release); return super.applyBatch(ops);
            }
        };
        var game = GameData.builder().dataAccess("game", access).ensureSchema(false).writeBatchSize(1).build();
        var repo = game.repository(Rows.class);
        repo.insert(new Row(1)); await(entered);
        var closer = task(game::close);
        until(() -> closer.thread.getState() == Thread.State.WAITING);
        assertFalse(access.closed);
        release.countDown(); closer.join();
        assertTrue(access.closed); assertEquals(List.of(1L), access.saved);
    }
    @Test void closeAllowsAnAlreadyStartedSafeRetryToFinish() {
        var firstAttempt = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (attempts.incrementAndGet() == 1) {
                    firstAttempt.countDown(); throw new DataException("retryable transaction");
                }
                return super.applyBatch(ops);
            }
            public WriteFailureAction classifyWriteFailure(Throwable error) { return WriteFailureAction.RETRY; }
        };
        var writer = new WriteBehindEngine(access, 1, 1, Duration.ofMillis(1), 1,
                (ops, error) -> fail(error));
        writer.submit(insert(1)); await(firstAttempt); writer.close();
        assertEquals(2, attempts.get()); assertEquals(List.of(1L), access.saved);
    }

    @Test void failureCallbackCannotDeadlockConcurrentGameClose() {
        var callbackEntered = new CountDownLatch(1); var releaseCallback = new CountDownLatch(1);
        var gameRef = new AtomicReference<GameData>();
        var rejected = new AtomicBoolean();
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) { throw new DataException("offline"); }
        };
        var game = GameData.builder().dataAccess("game", access).ensureSchema(false).writeBatchSize(1)
                .failureHandler((ops, error) -> {
                    callbackEntered.countDown(); await(releaseCallback);
                    assertThrows(IllegalStateException.class, () -> gameRef.get().close());
                    rejected.set(true);
                }).build();
        gameRef.set(game);
        game.repository(Rows.class).insert(new Row(1));
        await(callbackEntered);
        var closer = task(game::close);
        until(() -> closer.thread.getState() == Thread.State.WAITING);
        releaseCallback.countDown(); closer.join();
        assertTrue(rejected.get()); assertTrue(access.closed);
    }

    @Test void throwingFailureHandlerFallsBackToLogsAndDoesNotStopLaterWrites() {
        var buffer = new java.io.ByteArrayOutputStream();
        var originalErr = System.err;
        var access = new Access() {
            public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().id().equals(1L)) throw new DataException("database offline");
                return super.applyBatch(ops);
            }
        };
        try (var stderr = new java.io.PrintStream(buffer);
             var writer = engine(access, 2, 1, Duration.ofMillis(1), (ops, error) -> {
                 throw new IllegalStateException("log handler failed");
             })) {
            System.setErr(stderr);
            writer.submit(insert(1)); writer.submit(insert(2)); writer.flush();
            assertEquals(List.of(2L), access.saved);
            assertTrue(buffer.toString().contains("database offline"));
            assertTrue(buffer.toString().contains("log handler failed"));
        } finally { System.setErr(originalErr); }
    }

}
