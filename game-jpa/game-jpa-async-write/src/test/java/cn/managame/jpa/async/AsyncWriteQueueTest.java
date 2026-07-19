package cn.managame.jpa.async;

import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteRouter;
import cn.managame.jpa.core.write.WriteTask;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class AsyncWriteQueueTest {

    private static final String ENTITY = "player";

    /** 注册一个落默认目标、空落库器的合并通道，便于测试提交/摘取语义。 */
    private static AsyncWriteQueue mergeQueue(int capacity) {
        return mergeQueue(capacity, MetricsCollector.NOOP, WriteRouter.DEFAULT);
    }

    private static AsyncWriteQueue mergeQueue(int capacity, MetricsCollector metrics, WriteRouter router) {
        AsyncWriteQueue queue = new AsyncWriteQueue(capacity, metrics);
        queue.register(new WriteChannel.Merge(ENTITY, router, (op, tasks, ctx) -> { }));
        return queue;
    }

    private static AsyncWriteQueue appendQueue(int capacity, WriteRouter router) {
        AsyncWriteQueue queue = new AsyncWriteQueue(capacity);
        queue.register(new WriteChannel.Append(ENTITY, router, (entities, ctx) -> { }));
        return queue;
    }

    /** 摘取全部任务（跨所有物理表缓冲），用于断言。 */
    private static List<WriteTask> drainTasks(AsyncWriteQueue queue) {
        List<WriteTask> all = new ArrayList<>();
        for (TableBuffer.Drain drain : queue.drainReady()) {
            all.addAll(drain.saves());
            all.addAll(drain.deletes());
            queue.complete(drain.size());
            queue.finish(drain.buffer());
        }
        return all;
    }

    @Test
    public void drainsSubmittedTask() {
        AsyncWriteQueue queue = mergeQueue(1000);

        queue.submit(ENTITY, WriteTaskSubmitter.Op.DELETE, null, 100L);

        List<WriteTask> tasks = drainTasks(queue);
        assertEquals(1, tasks.size());
        assertEquals(100L, tasks.get(0).id());
        assertEquals(WriteTask.Op.DELETE, tasks.get(0).op());
    }

    @Test
    public void drainsOneSnapshotPerPhysicalTable() {
        AsyncWriteQueue queue = mergeQueue(100);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 2L);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 3L);

        List<TableBuffer.Drain> drains = queue.drainReady();

        assertEquals(1, drains.size(), "同一物理表一次只产生一个快照");
        assertEquals(3, drains.getFirst().saves().size());
        queue.complete(3);
        queue.finish(drains.getFirst().buffer());
    }

    @Test
    public void submitTimeRoutingPartitionsByPhysicalTable() {
        // 按 id 奇偶路由到两张物理表，提交期即落入不同缓冲对象。
        WriteRouter byParity = (entity, id, key) ->
                WriteDestination.of("default", "player_" + (((Long) id) % 2));
        AsyncWriteQueue queue = mergeQueue(100, MetricsCollector.NOOP, byParity);

        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 2L);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 3L);

        List<TableBuffer.Drain> drains = queue.drainReady();

        Set<String> tables = new java.util.HashSet<>();
        for (TableBuffer.Drain drain : drains) {
            tables.add(drain.buffer().context.physicalTableName());
            queue.complete(drain.size());
            queue.finish(drain.buffer());
        }
        assertEquals(Set.of("player_0", "player_1"), tables);
    }

    @Test
    public void rejectsNewKeysWhenConfiguredCapacityIsReached() {
        AsyncWriteQueue queue = mergeQueue(1);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);

        RejectedExecutionException ex = assertThrows(RejectedExecutionException.class,
                () -> queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 2L));
        assertTrue(ex.getMessage().contains("full"));
    }

    @Test
    public void configuredCapacityIsReportedAsMaxPendingGauge() {
        RecordingMetrics metrics = new RecordingMetrics();
        AsyncWriteQueue queue = mergeQueue(7, metrics, WriteRouter.DEFAULT);

        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 2L);
        assertEquals(2, queue.size());

        drainTasks(queue);

        assertEquals(0, metrics.gauge("asyncWrite.pending"));
        assertEquals(7, metrics.gauge("asyncWrite.maxPending"));
    }

    @Test
    public void pendingIncludesDrainedTasksUntilTheyComplete() {
        AsyncWriteQueue queue = mergeQueue(10);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);

        TableBuffer.Drain drain = queue.drainReady().getFirst();
        assertEquals(1, drain.size());
        assertEquals(1, queue.size(), "在途任务仍计入 pending");
        assertFalse(queue.isEmpty());

        queue.complete(1);
        queue.finish(drain.buffer());
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    public void stillMergesExistingKeyWhenBoundedQueueIsFull() {
        AsyncWriteQueue queue = mergeQueue(1);
        Object first = new Object();
        Object second = new Object();

        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, first, 1L);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, second, 1L);

        List<WriteTask> tasks = drainTasks(queue);
        assertEquals(1, tasks.size());
        assertSame(second, tasks.get(0).entity());
    }

    @Test
    public void resubmittedDrainedTaskDoesNotOverwriteNewerPendingUpdate() {
        AsyncWriteQueue queue = mergeQueue(1000);
        Object oldEntity = new Object();
        Object newEntity = new Object();

        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, oldEntity, 1L);
        TableBuffer.Drain drain = queue.drainReady().getFirst();
        WriteTask oldTask = drain.saves().getFirst();
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, newEntity, 1L);

        queue.requeue(drain.buffer(), List.of(oldTask));
        queue.finish(drain.buffer());

        List<WriteTask> tasks = drainTasks(queue);
        assertEquals(1, tasks.size());
        assertEquals(WriteTask.Op.SAVE, tasks.get(0).op());
        assertSame(newEntity, tasks.get(0).entity());
    }

    @Test
    public void resubmittedInsertMergesWithNewerPendingUpdateAsSaveWithNewEntity() {
        AsyncWriteQueue queue = mergeQueue(10);
        Object inserted = new Object();
        Object updated = new Object();

        queue.submit(ENTITY, WriteTaskSubmitter.Op.INSERT, inserted, 1L);
        TableBuffer.Drain drain = queue.drainReady().getFirst();
        WriteTask oldTask = drain.saves().getFirst();
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, updated, 1L);

        queue.requeue(drain.buffer(), List.of(oldTask));
        queue.finish(drain.buffer());

        List<WriteTask> tasks = drainTasks(queue);
        assertEquals(WriteTask.Op.SAVE, tasks.get(0).op());
        assertSame(updated, tasks.get(0).entity());
    }

    @Test
    public void appendDoesNotMergeAndKeepsEveryRecord() {
        AsyncWriteQueue queue = appendQueue(100, WriteRouter.DEFAULT);

        for (int i = 0; i < 5; i++) {
            queue.append(ENTITY, "log-" + i);
        }
        assertEquals(5, queue.size());

        List<WriteTask> tasks = drainTasks(queue);
        assertEquals(5, tasks.size(), "append 不按 id 合并，5 条全部保留");
    }

    @Test
    public void appendRoutesByExplicitRoutingKeyToPhysicalTable() {
        WriteRouter byKey = (entity, id, key) -> WriteDestination.of("default", "log_" + key);
        AsyncWriteQueue queue = appendQueue(100, byKey);

        queue.append(ENTITY, "a", 1);
        queue.append(ENTITY, "b", 1);
        queue.append(ENTITY, "c", 2);

        List<TableBuffer.Drain> drains = queue.drainReady();
        Map<String, Integer> perTable = new ConcurrentHashMap<>();
        for (TableBuffer.Drain drain : drains) {
            perTable.merge(drain.buffer().context.physicalTableName(), drain.size(), Integer::sum);
            queue.complete(drain.size());
            queue.finish(drain.buffer());
        }
        assertEquals(2, perTable.get("log_1"));
        assertEquals(1, perTable.get("log_2"));
    }

    @Test
    public void submitRejectsAppendChannel() {
        AsyncWriteQueue queue = appendQueue(10, WriteRouter.DEFAULT);
        assertThrows(IllegalStateException.class,
                () -> queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L));
    }

    @Test
    public void appendRejectsMergeChannel() {
        AsyncWriteQueue queue = mergeQueue(10);
        assertThrows(IllegalStateException.class, () -> queue.append(ENTITY, new Object()));
    }

    @Test
    public void submitWithoutRegisteredChannelFails() {
        AsyncWriteQueue queue = new AsyncWriteQueue(10);
        assertThrows(IllegalStateException.class,
                () -> queue.submit("unknown", WriteTaskSubmitter.Op.UPDATE, new Object(), 1L));
    }

    @Test
    public void concurrentSubmitAndDrainDoesNotLoseUniqueTasks() throws Exception {
        AsyncWriteQueue queue = mergeQueue(100_000);
        int threadCount = 4;
        int tasksPerThread = 200;
        int totalTasks = threadCount * tasksPerThread;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(threadCount);
        Set<Object> drainedIds = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < threadCount; thread++) {
            int offset = thread * tasksPerThread;
            futures.add(executor.submit(() -> {
                await(start);
                try {
                    for (int i = 0; i < tasksPerThread; i++) {
                        long id = offset + i;
                        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, id, id);
                    }
                } finally {
                    submitted.countDown();
                }
            }));
        }
        Future<?> drainer = executor.submit(() -> {
            await(start);
            while (submitted.getCount() > 0 || !queue.isEmpty()) {
                for (TableBuffer.Drain drain : queue.drainReady()) {
                    for (WriteTask task : drain.saves()) {
                        drainedIds.add(task.id());
                    }
                    for (WriteTask task : drain.deletes()) {
                        drainedIds.add(task.id());
                    }
                    queue.complete(drain.size());
                    queue.finish(drain.buffer());
                }
            }
        });

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        drainer.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(totalTasks, drainedIds.size());
    }

    @Test
    public void inactiveBuffersAreNotPutBackOnTheReadyQueue() {
        AsyncWriteQueue queue = mergeQueue(100);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);

        assertEquals(1, drainTasks(queue).size());
        assertTrue(queue.drainReady().isEmpty(), "空缓冲不会在每个周期被重复扫描");
        assertEquals(1, queue.bufferCount(), "保留路由缓冲以消除回收与提交竞态");

        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 2L);
        assertEquals(1, drainTasks(queue).size());
    }

    @Test
    public void rejectsSubmissionsAfterClose() {
        AsyncWriteQueue queue = mergeQueue(10);
        queue.close();

        RejectedExecutionException ex = assertThrows(RejectedExecutionException.class,
                () -> queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L));
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    public void closeForSubmissionsRejectsNewWritesButKeepsQueueDrainable() {
        AsyncWriteQueue queue = mergeQueue(10);
        Object entity = new Object();
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, entity, 1L);

        queue.closeForSubmissions();

        assertThrows(RejectedExecutionException.class,
                () -> queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 2L));

        List<WriteTask> tasks = drainTasks(queue);
        assertEquals(1, tasks.size());
        assertSame(entity, tasks.get(0).entity());
    }

    @Test
    public void internalRetryCanResubmitAfterCloseForSubmissions() {
        AsyncWriteQueue queue = mergeQueue(10);
        queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Object(), 1L);
        TableBuffer.Drain drain = queue.drainReady().getFirst();

        queue.closeForSubmissions();
        queue.requeue(drain.buffer(), drain.saves());
        queue.finish(drain.buffer());

        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());
    }

    @Test
    public void schedulerRejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> new FlushScheduler(new AsyncWriteQueue(), 0, 1));
    }

    @Test
    public void schedulerRejectsNegativeMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> new FlushScheduler(new AsyncWriteQueue(), 60_000, -1));
    }

    @Test
    public void schedulerRejectsNullQueue() {
        assertThrows(NullPointerException.class, () -> new FlushScheduler(null, 60_000, 1));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class RecordingMetrics implements MetricsCollector {
        private final Map<String, Long> gauges = new ConcurrentHashMap<>();

        @Override
        public void recordLatency(String operation, String entity, long millis) {
        }

        @Override
        public void recordCount(String operation, String entity, int count) {
        }

        @Override
        public void recordError(String operation, String entity, Throwable error) {
        }

        @Override
        public void recordGauge(String metric, String entity, long value) {
            gauges.put(metric, value);
        }

        private long gauge(String metric) {
            return gauges.getOrDefault(metric, 0L);
        }
    }
}
