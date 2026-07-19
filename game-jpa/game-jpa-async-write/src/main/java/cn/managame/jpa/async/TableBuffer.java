package cn.managame.jpa.async;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个写通道在单个物理目标上的缓冲。每个缓冲最多允许一个在途刷盘任务。 */
abstract class TableBuffer {

    final WriteDestination destination;
    final ExecutorContext context;

    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean enqueued = new AtomicBoolean();
    private final AtomicBoolean inFlight = new AtomicBoolean();

    TableBuffer(WriteDestination destination) {
        this.destination = destination;
        this.context = destination.toContext();
    }

    /** 提交后标记为活跃；返回 true 时调用方应把本缓冲加入 ready queue。 */
    final boolean markDirty() {
        dirty.set(true);
        return !inFlight.get() && enqueued.compareAndSet(false, true);
    }

    /** ready queue 摘取后尝试取得该物理表的唯一刷盘权。 */
    final boolean beginDrain() {
        enqueued.set(false);
        if (!inFlight.compareAndSet(false, true)) {
            return false;
        }
        dirty.set(false);
        return true;
    }

    /** 刷盘完成后释放物理表；返回 true 时仍有后续数据，应重新加入 ready queue。 */
    final boolean finishDrain() {
        inFlight.set(false);
        return (dirty.get() || !isEmpty()) && enqueued.compareAndSet(false, true);
    }

    abstract Drain drain();

    abstract boolean isEmpty();

    /**
     * 把旧失败任务放回当前缓冲。合并型缓冲保留已经存在的更新，并返回被新状态覆盖的旧任务数。
     */
    abstract int requeue(List<WriteTask> tasks);

    abstract void flush(WriteTask.Op op, List<WriteTask> tasks);

    /** SAVE/DELETE 最终态可重复执行；append-only 默认不具备该保证。 */
    abstract boolean replaySafe();

    /** 批量失败是否保证没有部分提交，用于安全拆批定位 append-only 坏记录。 */
    abstract boolean atomicBatch();

    record Drain(TableBuffer buffer, List<WriteTask> saves, List<WriteTask> deletes) {
        int size() {
            return saves.size() + deletes.size();
        }

        boolean isEmpty() {
            return saves.isEmpty() && deletes.isEmpty();
        }
    }
}
