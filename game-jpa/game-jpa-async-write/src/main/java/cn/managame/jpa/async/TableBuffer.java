package cn.managame.jpa.async;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.write.WriteDestination;
import cn.managame.jpa.core.write.WriteTask;

import java.util.List;

/**
 * 单个物理目标 {@code (dataSource, physicalTable)} 的写缓冲对象——即“路由到同一张数据表的缓存对象”。
 * <p>
 * 提交期路由后写任务落入对应缓冲；刷盘期按 maxBatchSize 切成 {@link FlushUnit}。{@link ExecutorContext}
 * 在创建时按目标算好一次并缓存（routingKey 置 null，批量写层不使用）。两种实现：
 * {@link MergeBuffer}（按 id 合并）与 {@link AppendBuffer}（只追加）。
 */
abstract class TableBuffer {

    final WriteDestination dest;
    final ExecutorContext ctx;

    /** 连续空闲（drain 出 0 条）的轮数，仅由单线程 drainer 读写，无需同步。 */
    private int idleCycles;

    protected TableBuffer(WriteDestination dest) {
        this.dest = dest;
        this.ctx = dest.toContext();
    }

    /** drain 出非空内容后调用，重置空闲计数。 */
    void resetIdle() {
        idleCycles = 0;
    }

    /** drain 出空后调用，累计空闲轮数；达到阈值返回 true 表示可回收该缓冲对象。 */
    boolean markIdleAndShouldEvict(int idleEvictThreshold) {
        return ++idleCycles >= idleEvictThreshold;
    }

    /** 摘取当前缓冲内容，按 op + maxBatchSize 切片为刷盘单元；并发提交不会丢失（见各实现）。 */
    abstract List<FlushUnit> drainSlices(int maxBatchSize);

    /**
     * 失败重试回灌一条任务。
     *
     * @return true 表示这是新增条目（调用方据此自增 pending 计数）
     */
    abstract boolean reAdd(WriteTask task);
}
