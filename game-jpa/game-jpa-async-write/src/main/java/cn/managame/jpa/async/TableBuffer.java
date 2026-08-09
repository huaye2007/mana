package cn.managame.jpa.async;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个写通道在单个物理目标上的缓冲。
 * <p>
 * 只有一个状态位 {@link #flushing}：保证同一物理表最多一个在途批次。调度器每轮扫描全部缓冲、
 * 跳过空的，因此不需要 dirty/ready 之类的唤醒协议——缓冲数量的上界是物理表数量，扫描成本相对
 * 一次数据库往返可以忽略。
 */
abstract class TableBuffer {

    final WriteDestination destination;
    final ExecutorContext context;

    private final AtomicBoolean flushing = new AtomicBoolean();

    TableBuffer(WriteDestination destination) {
        this.destination = destination;
        this.context = destination.toContext();
    }

    /**
     * 尝试取得该物理表的刷盘权。空缓冲直接跳过（不做 CAS），同表已有在途批次时也跳过。
     * <p>
     * 「判空之后、CAS 之前」进来的新数据会漏过本轮，下一轮扫描自然接上：刷盘本就是按周期
     * 批量攒写的，晚一个周期没有语义影响，换来的是不需要任何唤醒/回灌协议。
     */
    final boolean tryBeginFlush() {
        return !isEmpty() && flushing.compareAndSet(false, true);
    }

    /** 释放刷盘权。 */
    final void endFlush() {
        flushing.set(false);
    }

    /** 摘取当前全部任务。仅在持有刷盘权时调用，因此与其它 drain 互斥。 */
    abstract Drain drain();

    abstract boolean isEmpty();

    /**
     * 把失败任务放回缓冲。合并型缓冲保留缓冲中已经存在的更新状态（新状态优先），
     * 并返回被新状态覆盖掉的旧任务数，供调用方扣减 pending。
     */
    abstract int requeue(List<WriteTask> tasks);

    abstract void flush(WriteTask.Op op, List<WriteTask> tasks);

    /**
     * 本通道的写是否幂等、可以无条件重放。
     * <p>
     * 合并通道是 SAVE/DELETE 最终态语义（upsert / delete by id），重放安全；
     * append-only 追加通道重放会产生重复行，不安全。
     */
    abstract boolean idempotent();

    /**
     * 批次失败时是否保证整批已回滚（没有部分提交）。
     * <p>
     * 只对<b>确定性</b>失败有意义：连接中断/超时导致 commit 结果未知时，即使声明了原子批次
     * 也不能推断已回滚。判定见 {@code FlushScheduler#replayable}。
     */
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
