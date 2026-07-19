package cn.managame.jpa.core.write;

import cn.managame.jpa.core.executor.ExecutorContext;

import java.util.List;

/**
 * 追加通道（日志等 append-only 实体）的批量落库 SPI。
 * <p>
 * 追加缓冲不做 id 合并，按 {@code (dataSource, physicalTable)} 分桶后按 {@code maxBatchSize}
 * 切片；调度器对每个切片回调本接口。入参 {@code entities} 同物理目标，执行层按 {@code ctx}
 * 选库选表，整批走 batchInsert（命名遵循 batchXxx）。
 */
@FunctionalInterface
public interface AppendFlusher {

    void flush(List<Object> entities, ExecutorContext ctx);

    /**
     * 批次失败时是否保证本批没有部分提交。默认 false；只有真正以事务/savepoint 包裹整个批次的实现才可返回 true。
     * 即使返回 true，连接中断造成的 commit 结果未知仍不会自动重放 append-only 数据。
     */
    default boolean atomicBatch() {
        return false;
    }
}
