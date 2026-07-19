package cn.managame.jpa.async;

import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.BatchFlusher;
import cn.managame.jpa.core.write.WriteChannel;
import cn.managame.jpa.core.write.WriteRouter;
import cn.managame.jpa.core.write.WriteTask;
import cn.managame.jpa.core.write.WriteTaskSubmitter;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证 FlushScheduler.maxBatchSize 在大批量下按物理表切片的行为，
 * 保证不会把上万条任务塞进单次落库调用（防 DB 穿透批量上限）。
 */
public class FlushSchedulerBatchSizeTest {

    private static final String ENTITY = "player";

    private static void registerMerge(AsyncWriteQueue queue, BatchFlusher flusher) {
        queue.register(new WriteChannel.Merge(ENTITY, WriteRouter.DEFAULT, flusher));
    }

    @Test
    public void chunksLargeGroupIntoMaxBatchSizeBatches() {
        AsyncWriteQueue queue = new AsyncWriteQueue(20_000);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1,
                FlushThreadMode.PLATFORM, 1, MetricsCollector.NOOP, 500);

        List<Integer> chunkSizes = new CopyOnWriteArrayList<>();
        AtomicInteger totalTasks = new AtomicInteger();
        registerMerge(queue, (op, tasks, ctx) -> {
            chunkSizes.add(tasks.size());
            totalTasks.addAndGet(tasks.size());
        });

        try {
            for (long id = 0; id < 1234; id++) {
                queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(id), id);
            }
            scheduler.flush();

            assertEquals(1234, totalTasks.get(), "All tasks must be handled");
            assertEquals(3, chunkSizes.size(), "Chunk count = ceil(1234/500)");
            for (int size : chunkSizes) {
                assertTrue(size <= 500, "Each chunk must respect maxBatchSize");
            }
            assertTrue(queue.isEmpty(), "Queue drained after flush");
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void chunkFailureDoesNotBlockOtherChunks() {
        AsyncWriteQueue queue = new AsyncWriteQueue(2000);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 0,
                FlushThreadMode.PLATFORM, 2, MetricsCollector.NOOP, 100);

        AtomicInteger ok = new AtomicInteger();
        List<Long> failedIds = new CopyOnWriteArrayList<>();
        scheduler.onFailure(task -> failedIds.add((Long) task.id()));
        registerMerge(queue, (op, tasks, ctx) -> {
            // 含 id=150 的那一片整片失败；降级单条重试时 id=150 仍失败，最终进 failureHandler，
            // 同片其余 99 条单条成功。其余分片正常成功。断言与分片顺序无关。
            for (WriteTask t : tasks) {
                if (((Long) t.id()) == 150L) {
                    throw new IllegalStateException("simulated chunk failure");
                }
            }
            ok.addAndGet(tasks.size());
        });

        try {
            for (long id = 0; id < 300; id++) {
                queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(id), id);
            }
            scheduler.flush();

            assertEquals(299, ok.get());
            assertEquals(List.of(150L), new ArrayList<>(failedIds));
            assertTrue(queue.isEmpty(), "Queue drained after fall-back to single retry");
        } finally {
            scheduler.close();
        }
    }

    @Test
    public void flushReturnsWhenBatchHandlerTimesOut() throws Exception {
        AsyncWriteQueue queue = new AsyncWriteQueue(10);
        FlushScheduler scheduler = new FlushScheduler(queue, 60_000, 1,
                FlushThreadMode.PLATFORM, 1, MetricsCollector.NOOP, 500, 50);

        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        registerMerge(queue, (op, tasks, ctx) -> {
            handlerStarted.countDown();
            try {
                releaseHandler.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        try {
            queue.submit(ENTITY, WriteTaskSubmitter.Op.UPDATE, new Player(1L), 1L);
            long start = System.currentTimeMillis();
            scheduler.flush();
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));
            assertTrue(elapsed < 1000, "flush should not block forever on a hung batch");
            assertFalse(queue.isEmpty(), "结果未知的在途任务必须继续计入 pending");
            assertEquals(1, queue.size());
        } finally {
            releaseHandler.countDown();
            scheduler.close();
        }
    }

    private record Player(long id) implements java.io.Serializable {
    }
}
