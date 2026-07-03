package com.github.huaye2007.mana.jpa.core.write;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;

import java.util.List;

/**
 * 合并通道（实体缓存）的批量落库 SPI。
 * <p>
 * 异步缓冲已按 id 合并到最终态，并按 {@code (dataSource, physicalTable)} 分桶后按
 * {@code maxBatchSize} 切片；调度器对每个切片回调本接口。入参 {@code tasks} 同 op、同物理目标，
 * 执行层按 {@code ctx} 选库选表，对 SAVE 走 upsert、DELETE 走批量删除（命名遵循 batchXxx）。
 */
@FunctionalInterface
public interface BatchFlusher {

    void flush(WriteTask.Op op, List<WriteTask> tasks, ExecutorContext ctx);
}
