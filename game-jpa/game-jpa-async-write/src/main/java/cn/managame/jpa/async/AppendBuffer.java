package cn.managame.jpa.async;

import cn.managame.jpa.core.write.AppendFlusher;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 追加缓冲：日志等 append-only 写回，只入队、不按 id 合并。
 * <p>
 * 用无锁 {@link ConcurrentLinkedQueue}；摘取按 {@code poll} 逐条出队切成 maxBatchSize 批，
 * 与并发 {@code offer} 互不阻塞、不丢失。任务包装为 {@link WriteTask}（仅复用其重试计数，op 不参与）。
 */
final class AppendBuffer extends TableBuffer {

    private final ConcurrentLinkedQueue<WriteTask> items = new ConcurrentLinkedQueue<>();
    private final AppendFlusher flusher;

    AppendBuffer(WriteDestination dest, AppendFlusher flusher) {
        super(dest);
        this.flusher = flusher;
    }

    void add(WriteTask task) {
        items.offer(task);
    }

    @Override
    boolean reAdd(WriteTask task) {
        items.offer(task);
        return true;
    }

    @Override
    List<FlushUnit> drainSlices(int maxBatchSize) {
        List<FlushUnit> units = new ArrayList<>();
        while (true) {
            List<WriteTask> slice = new ArrayList<>(Math.min(maxBatchSize, 256));
            WriteTask task;
            while (slice.size() < maxBatchSize && (task = items.poll()) != null) {
                slice.add(task);
            }
            if (slice.isEmpty()) {
                break;
            }
            units.add(new FlushUnit(this, slice,
                    subset -> flusher.flush(subset.stream().map(WriteTask::entity).toList(), ctx)));
            if (slice.size() < maxBatchSize) {
                break; // 队列已抽干
            }
        }
        return units;
    }
}
