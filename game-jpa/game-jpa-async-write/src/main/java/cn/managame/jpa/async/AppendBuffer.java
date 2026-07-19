package cn.managame.jpa.async;

import cn.managame.jpa.core.write.AppendFlusher;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/** append-only 缓冲。失败结果可能不确定，因此不声明可安全重放。 */
final class AppendBuffer extends TableBuffer {

    private final ConcurrentLinkedDeque<WriteTask> tasks = new ConcurrentLinkedDeque<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AppendFlusher flusher;

    AppendBuffer(WriteDestination destination, AppendFlusher flusher) {
        super(destination);
        this.flusher = flusher;
    }

    void add(WriteTask task) {
        tasks.addLast(task);
        queued.incrementAndGet();
    }

    @Override
    Drain drain() {
        int snapshotSize = queued.get();
        List<WriteTask> saves = new ArrayList<>(snapshotSize);
        for (int i = 0; i < snapshotSize; i++) {
            WriteTask task = tasks.pollFirst();
            if (task == null) {
                break;
            }
            queued.decrementAndGet();
            saves.add(task);
        }
        return new Drain(this, saves, List.of());
    }

    @Override
    boolean isEmpty() {
        return queued.get() == 0;
    }

    @Override
    int requeue(List<WriteTask> retryTasks) {
        for (int i = retryTasks.size() - 1; i >= 0; i--) {
            tasks.addFirst(retryTasks.get(i));
            queued.incrementAndGet();
        }
        return 0;
    }

    @Override
    void flush(WriteTask.Op op, List<WriteTask> batch) {
        List<Object> entities = new ArrayList<>(batch.size());
        for (WriteTask task : batch) {
            entities.add(task.entity());
        }
        flusher.flush(entities, context);
    }

    @Override
    boolean replaySafe() {
        return false;
    }

    @Override
    boolean atomicBatch() {
        return flusher.atomicBatch();
    }
}
