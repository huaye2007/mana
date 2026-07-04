package cn.managame.jpa.rdb.repository;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.AppendFlusher;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;

import java.util.List;

/**
 * 日志追加通道落库器：把异步缓冲攒下的 append-only 记录整批 {@code batchInsert} 写入物理表。
 * <p>
 * 物理目标已在提交期路由好、由调度器以 {@link ExecutorContext} 传入；列默认值由执行器
 * {@code batchInsert} 内部兜底，字段超长由执行器自动加宽。
 */
public class RdbLogBatchFlusher implements AppendFlusher {

    private final RdbEntityMetadata metadata;
    private final RdbExecutor executor;
    private final MetricsCollector metrics;

    public RdbLogBatchFlusher(RdbEntityMetadata metadata, RdbExecutor executor, MetricsCollector metrics) {
        this.metadata = metadata;
        this.executor = executor;
        this.metrics = metrics != null ? metrics : MetricsCollector.NOOP;
    }

    @Override
    public void flush(List<Object> entities, ExecutorContext ctx) {
        // 埋点按物理表打标签（分表日志定位热点表），非分片回退到逻辑名。
        String tag = ctx.physicalTableName() != null ? ctx.physicalTableName() : metadata.logicalName();
        metrics.instrument("asyncWrite.append", tag, () -> {
            executor.batchInsert(metadata, entities, ctx);
            metrics.recordCount("asyncWrite.append", tag, entities.size());
        });
    }
}
