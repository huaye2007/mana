package cn.managame.jpa.async;

import cn.managame.jpa.core.write.BatchFlusher;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** 按主键只保留最新 SAVE/DELETE 最终态的物理表缓冲。 */
final class MergeBuffer extends TableBuffer {

    enum AddResult { ADDED, MERGED, FULL }

    private final ConcurrentHashMap<Object, WriteTask> tasks = new ConcurrentHashMap<>();
    private final BatchFlusher flusher;
    private final AsyncWriteQueue owner;

    MergeBuffer(WriteDestination destination, BatchFlusher flusher, AsyncWriteQueue owner) {
        super(destination);
        this.flusher = flusher;
        this.owner = owner;
    }

    /** CAS 更新最终态；同一 key 的新业务写会替换旧重试状态并重置重试次数。 */
    AddResult add(WriteTask incoming) {
        Object id = incoming.id();
        while (true) {
            WriteTask current = tasks.get(id);
            if (current != null) {
                if (tasks.replace(id, current, incoming)) {
                    return AddResult.MERGED;
                }
                continue;
            }
            if (!owner.tryReserve()) {
                return AddResult.FULL;
            }
            WriteTask raced = tasks.putIfAbsent(id, incoming);
            if (raced == null) {
                return AddResult.ADDED;
            }
            owner.releaseReservation();
        }
    }

    @Override
    Drain drain() {
        int snapshotSize = tasks.size();
        List<WriteTask> saves = new ArrayList<>(snapshotSize);
        List<WriteTask> deletes = new ArrayList<>();
        int drained = 0;
        for (Object id : tasks.keySet()) {
            if (drained >= snapshotSize) {
                break;
            }
            WriteTask task = tasks.remove(id);
            if (task != null) {
                (task.op() == WriteTask.Op.SAVE ? saves : deletes).add(task);
                drained++;
            }
        }
        return new Drain(this, saves, deletes);
    }

    @Override
    boolean isEmpty() {
        return tasks.isEmpty();
    }

    @Override
    int requeue(List<WriteTask> retryTasks) {
        int superseded = 0;
        for (WriteTask task : retryTasks) {
            if (tasks.putIfAbsent(task.id(), task) != null) {
                superseded++;
            }
        }
        return superseded;
    }

    @Override
    void flush(WriteTask.Op op, List<WriteTask> batch) {
        flusher.flush(op, batch, context);
    }

    @Override
    boolean replaySafe() {
        return true;
    }

    @Override
    boolean atomicBatch() {
        return true;
    }
}
