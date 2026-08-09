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

    /**
     * CAS 更新最终态。同一 key 已有任务时整对象替换（不占新的 pending 配额），
     * 并让新任务继承旧任务的重试预算，避免持续更新的坏记录永远重试不完。
     */
    AddResult add(WriteTask incoming) {
        Object id = incoming.id();
        while (true) {
            WriteTask current = tasks.get(id);
            if (current != null) {
                incoming.inheritRetryCount(current);
                if (tasks.replace(id, current, incoming)) {
                    return AddResult.MERGED;
                }
                continue;
            }
            if (!owner.tryReserve()) {
                return AddResult.FULL;
            }
            if (tasks.putIfAbsent(id, incoming) == null) {
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
            WriteTask existing = tasks.putIfAbsent(task.id(), task);
            if (existing != null) {
                // 缓冲里已有更新的状态，旧任务丢弃；但重试预算要交接给新状态，
                // 否则同一 key 反复失败时预算会被业务写不断清零。
                existing.inheritRetryCount(task);
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
    boolean idempotent() {
        return true;
    }

    @Override
    boolean atomicBatch() {
        return true;
    }
}
