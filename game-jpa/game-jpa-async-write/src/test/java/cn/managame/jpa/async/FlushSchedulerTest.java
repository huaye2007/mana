package cn.managame.jpa.async;

import cn.managame.jpa.core.exception.ConfigurationException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.BatchFlusher;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteRouter;
import cn.managame.jpa.core.write.WriteTask;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FlushSchedulerTest {

    private static final String ENTITY = "player";

    private static void registerMerge(AsyncWriteQueue queue, BatchFlusher flusher) {
        queue.register(new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, flusher));
    }

    @Test
    public void dropsNonRetriableFailureImmediately() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 3);
        AtomicReference<WriteTask> failed = new AtomicReference<>();
        try {
            scheduler.onFailure(failed::set);
            registerMerge(queue, (op, tasks, ctx) -> {
                throw new IllegalStateException("constraint violation");
            });

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            scheduler.flush();

            assertNotNull(failed.get(), "failure handler must be notified");
            assertEquals(ENTITY, failed.get().entityName());
            assertTrue(queue.isEmpty(), "non-retriable failure is dropped without retry");
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void retriesRetriableFailureInsteadOfDropping() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 3);
        AtomicReference<WriteTask> failed = new AtomicReference<>();
        try {
            scheduler.onFailure(failed::set);
            registerMerge(queue, (op, tasks, ctx) -> {
                throw new RetriableWriteException("transient lock", new RuntimeException("deadlock"));
            });

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            scheduler.flush();

            assertNull(failed.get(), "retriable failure must not be reported before retries are exhausted");
            assertFalse(queue.isEmpty(), "retriable failure is re-queued for another attempt");
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void flushesRegisteredMergeChannel() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1);
        AtomicInteger writes = new AtomicInteger();
        try {
            registerMerge(queue, (op, tasks, ctx) -> {
                assertEquals(WriteTask.Op.SAVE, op);
                assertEquals(1, tasks.size());
                writes.incrementAndGet();
            });

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            scheduler.flush();

            assertEquals(1, writes.get());
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void closeRetriesPendingTasksBeforeStoppingExecutor() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 3);
        AtomicInteger attempts = new AtomicInteger();
        boolean[] closed = { false };
        try {
            registerMerge(queue, (op, tasks, ctx) -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RetriableWriteException("temporary failure", new RuntimeException());
                }
            });

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            scheduler.close();
            closed[0] = true;

            assertEquals(3, attempts.get());
            assertTrue(queue.isEmpty());
        } finally {
            if (!closed[0]) {
                scheduler.close();
            }
        }
    }

    @Test
    public void platformThreadModeAlsoFlushes() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1,
                FlushThreadMode.PLATFORM, 2, MetricsCollector.NOOP);
        AtomicInteger writes = new AtomicInteger();
        try {
            registerMerge(queue, (op, tasks, ctx) -> writes.addAndGet(tasks.size()));

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(2L), 2L);
            scheduler.flush();

            assertEquals(2, writes.get());
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void configurationErrorIsClassifiedAndCountedSeparately() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        CountingMetrics metrics = new CountingMetrics();
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 3, FlushThreadMode.VIRTUAL, 0, metrics);
        AtomicReference<WriteTask> failed = new AtomicReference<>();
        try {
            scheduler.onFailure(failed::set);
            registerMerge(queue, (op, tasks, ctx) -> {
                throw new ConfigurationException("DataSource not registered: 'ds_7'");
            });

            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            scheduler.flush();

            assertNotNull(failed.get(), "配置错误也通知 failureHandler");
            assertTrue(queue.isEmpty(), "配置错误不重试，直接丢弃");
            assertEquals(1, metrics.count("asyncWrite.misconfiguration"), "单列为配置错误");
            assertEquals(0, metrics.count("asyncWrite.permanentFailure"), "不与普通数据错误混计");
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void maxConcurrencyCapsParallelPhysicalTables() {
        AsyncWriteQueue queue = new AsyncWriteQueue(100);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        // 每个 id 路由到独立物理表 → 多个刷盘单元；并发上限=1 应使其串行。
        queue.register(new WriteChannel.Merge(ENTITY,
                (entity, id, key) -> WriteDestination.of("default", "t" + id),
                (op, tasks, ctx) -> {
                    int now = active.incrementAndGet();
                    maxObserved.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                }));
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1,
                FlushThreadMode.PLATFORM, 4, MetricsCollector.NOOP, 500,
                FlushScheduler.DEFAULT_BATCH_TIMEOUT_MILLIS, 1);
        try {
            for (long id = 1; id <= 4; id++) {
                queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(id), id);
            }
            scheduler.flush();

            assertEquals(1, maxObserved.get(), "并发上限=1 时刷盘单元串行执行");
            assertTrue(queue.isEmpty());
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void rejectsNullFlusherChannel() {
        assertThrows(NullPointerException.class,
                () -> new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, null));
    }

    @Test
    public void rejectsNullFailureHandler() {
        assertThrows(NullPointerException.class, () -> {
            FlushScheduler scheduler = new FlushScheduler(new AsyncWriteQueue(100), 60_000, 1);
            try {
                scheduler.onFailure(null);
            } finally {
                scheduler.close();
            }
        });
    }

    private record Player(long id) implements java.io.Serializable {
    }

    private static final class CountingMetrics implements MetricsCollector {
        private final Map<String, Integer> counts = new ConcurrentHashMap<>();

        @Override
        public void recordLatency(String operation, String entity, long millis) {
        }

        @Override
        public void recordCount(String operation, String entity, int count) {
            counts.merge(operation, count, Integer::sum);
        }

        @Override
        public void recordError(String operation, String entity, Throwable error) {
        }

        int count(String operation) {
            return counts.getOrDefault(operation, 0);
        }
    }
}
