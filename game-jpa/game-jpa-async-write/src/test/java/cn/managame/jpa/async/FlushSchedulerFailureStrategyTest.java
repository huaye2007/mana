package cn.managame.jpa.async;

import cn.managame.jpa.core.exception.ConnectionException;
import cn.managame.jpa.core.exception.DataTooLargeException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.AppendFlusher;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteRouter;
import cn.managame.jpa.core.write.WriteTask;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlushSchedulerFailureStrategyTest {

    private static final String ENTITY = "player";

    @Test
    public void samePhysicalTableChunksExecuteSequentially() {
        AsyncWriteQueue queue = new AsyncWriteQueue(1_000);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        queue.register(new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, (op, tasks, ctx) -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        }));
        FlushScheduler scheduler = scheduler(queue, 4, 100, 1);
        try {
            for (long id = 0; id < 300; id++) {
                queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(id, 0), id);
            }

            scheduler.flush();

            assertEquals(1, maxActive.get());
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void differentPhysicalTablesCanExecuteInParallel() {
        AsyncWriteQueue queue = new AsyncWriteQueue(10);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch bothEntered = new CountDownLatch(2);
        queue.register(new WriteChannel.Merge(ENTITY,
                (entity, id, key) -> WriteDestination.of("default", "player_" + id),
                (op, tasks, ctx) -> {
                    int current = active.incrementAndGet();
                    maxActive.accumulateAndGet(current, Math::max);
                    bothEntered.countDown();
                    try {
                        bothEntered.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                }));
        FlushScheduler scheduler = scheduler(queue, 2, 100, 1);
        try {
            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1, 0), 1L);
            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(2, 0), 2L);

            scheduler.flush();

            assertEquals(2, maxActive.get());
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void transientBatchFailureRequeuesWholeBatchWithoutSingleWrites() {
        AsyncWriteQueue queue = new AsyncWriteQueue(200);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger observedBatchSize = new AtomicInteger();
        queue.register(new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, (op, tasks, ctx) -> {
            calls.incrementAndGet();
            observedBatchSize.set(tasks.size());
            throw new RetriableWriteException("temporary", new RuntimeException());
        }));
        FlushScheduler scheduler = scheduler(queue, 2, 200, 2);
        scheduler.onFailure(task -> { });
        try {
            for (long id = 0; id < 100; id++) {
                queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(id, 0), id);
            }

            scheduler.flush();

            assertEquals(1, calls.get(), "瞬时批次错误不应降级为 100 次单写");
            assertEquals(100, observedBatchSize.get());
            assertEquals(100, queue.size());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void dataTooLargeFailureUsesBinaryIsolationAndRetriesOnlyBadRow() {
        AsyncWriteQueue queue = new AsyncWriteQueue(200);
        AtomicInteger calls = new AtomicInteger();
        Set<Long> written = ConcurrentHashMap.newKeySet();
        queue.register(new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, (op, tasks, ctx) -> {
            calls.incrementAndGet();
            if (tasks.stream().anyMatch(task -> task.id().equals(50L))) {
                throw new DataTooLargeException("too large", new RuntimeException());
            }
            tasks.forEach(task -> written.add((Long) task.id()));
        }));
        FlushScheduler scheduler = scheduler(queue, 2, 200, 1);
        AtomicReference<WriteTask> failed = new AtomicReference<>();
        scheduler.onFailure(failed::set);
        try {
            for (long id = 0; id < 100; id++) {
                queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(id, 0), id);
            }

            scheduler.flush();

            assertEquals(99, written.size());
            assertEquals(1, queue.size(), "只回灌超长记录");
            assertTrue(calls.get() < 25, "二分隔离不应退化为逐条尝试");

            scheduler.flush();
            assertTrue(queue.isEmpty());
            assertEquals(50L, failed.get().id());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void newerStateSupersedesOlderFailedInFlightState() throws Exception {
        AsyncWriteQueue queue = new AsyncWriteQueue(10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Object> written = new AtomicReference<>();
        queue.register(new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, (op, tasks, ctx) -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RetriableWriteException("temporary", new RuntimeException());
            }
            written.set(tasks.getFirst().entity());
        }));
        FlushScheduler scheduler = scheduler(queue, 1, 100, 2);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Player oldState = new Player(1, 1);
            Player newState = new Player(1, 2);
            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, oldState, 1L);
            Future<?> flush = caller.submit(scheduler::flush);
            assertTrue(started.await(1, TimeUnit.SECONDS));

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, newState, 1L);
            release.countDown();
            flush.get(2, TimeUnit.SECONDS);

            assertEquals(1, queue.size());
            scheduler.flush();
            assertSame(newState, written.get());
            assertTrue(queue.isEmpty());
        } finally {
            release.countDown();
            caller.shutdownNow();
            scheduler.close();
        }
    }

    @Test
    public void appendOnlyFailureIsNotBlindlyReplayed() {
        AsyncWriteQueue queue = new AsyncWriteQueue(10);
        AtomicInteger calls = new AtomicInteger();
        queue.register(new WriteChannel.Append("log", WriteRouter.DEFAULT, new AppendFlusher() {
            @Override
            public void flush(List<Object> entities, cn.managame.jpa.core.executor.ExecutorContext ctx) {
                calls.incrementAndGet();
                throw new ConnectionException("commit outcome unknown", "default", new RuntimeException());
            }

            @Override
            public boolean atomicBatch() {
                return true;
            }
        }));
        FlushScheduler scheduler = scheduler(queue, 2, 100, 3);
        AtomicInteger failed = new AtomicInteger();
        scheduler.onFailure(task -> failed.incrementAndGet());
        try {
            queue.append("log", "a");
            queue.append("log", "b");
            queue.append("log", "c");

            scheduler.flush();

            assertEquals(1, calls.get());
            assertEquals(3, failed.get());
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void atomicAppendBatchCanSplitDataErrorsWithoutReplayingGoodRows() {
        AsyncWriteQueue queue = new AsyncWriteQueue(10);
        Set<Object> written = ConcurrentHashMap.newKeySet();
        queue.register(new WriteChannel.Append("log", WriteRouter.DEFAULT, new AppendFlusher() {
            @Override
            public void flush(List<Object> entities, cn.managame.jpa.core.executor.ExecutorContext ctx) {
                if (entities.contains("bad")) {
                    throw new DataTooLargeException("too large", new RuntimeException());
                }
                written.addAll(entities);
            }

            @Override
            public boolean atomicBatch() {
                return true;
            }
        }));
        FlushScheduler scheduler = scheduler(queue, 1, 100, 0);
        AtomicReference<WriteTask> failed = new AtomicReference<>();
        scheduler.onFailure(failed::set);
        try {
            queue.append("log", "a");
            queue.append("log", "bad");
            queue.append("log", "b");

            scheduler.flush();

            assertEquals(Set.of("a", "b"), written);
            assertEquals("bad", failed.get().entity());
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    private static FlushScheduler scheduler(AsyncWriteQueue queue, int concurrency,
            int batchSize, int maxRetries) {
        return new FlushScheduler(queue, 60_000, maxRetries,
                FlushThreadMode.PLATFORM, concurrency, MetricsCollector.NOOP, batchSize);
    }

    private record Player(long id, int value) {
    }
}
