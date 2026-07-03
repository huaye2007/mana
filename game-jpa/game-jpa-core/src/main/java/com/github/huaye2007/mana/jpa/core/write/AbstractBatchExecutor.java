package com.github.huaye2007.mana.jpa.core.write;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;

import java.util.List;
import java.util.Locale;

/**
 * 合并通道批量落库公共基类。
 * <p>
 * 分片路由已前移到提交期（{@link WriteRouter}），缓冲对象按 {@code (dataSource, physicalTable)}
 * 分桶后传入已解析好的 {@link ExecutorContext}，本基类只负责「op → batchSave/batchDelete + 埋点」，
 * 落库语义差异（RDB upsert / DocDB save）由子类模板方法填充。
 *
 * @param <M> 模型层元数据类型（RdbEntityMetadata / DocEntityMetadata）
 */
public abstract class AbstractBatchExecutor<M extends EntityMetadata> implements BatchFlusher {

    protected final M metadata;
    private final MetricsCollector metrics;

    protected AbstractBatchExecutor(M metadata, MetricsCollector metrics) {
        this.metadata = metadata;
        this.metrics = metrics != null ? metrics : MetricsCollector.NOOP;
    }

    /** SAVE 批量落库（RDB: upsert，DocDB: save）。 */
    protected abstract void batchSave(List<Object> entities, ExecutorContext ctx);

    /** DELETE 批量落库。 */
    protected abstract void batchDelete(List<Object> ids, ExecutorContext ctx);

    @Override
    public void flush(WriteTask.Op op, List<WriteTask> tasks, ExecutorContext ctx) {
        String metricName = "asyncWrite." + op.name().toLowerCase(Locale.ROOT);
        // 埋点按物理表打标签（分表时定位热点表），非分片回退到逻辑名。
        String tag = ctx.physicalTableName() != null ? ctx.physicalTableName() : metadata.logicalName();
        metrics.instrument(metricName, tag, () -> {
            switch (op) {
                case SAVE -> batchSave(tasks.stream().map(WriteTask::entity).toList(), ctx);
                case DELETE -> batchDelete(tasks.stream().map(WriteTask::id).toList(), ctx);
            }
            metrics.recordCount(metricName, tag, tasks.size());
        });
    }
}
