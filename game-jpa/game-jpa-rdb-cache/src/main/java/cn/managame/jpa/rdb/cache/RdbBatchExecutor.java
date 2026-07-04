package cn.managame.jpa.rdb.cache;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.core.metrics.MetricsCollector;
import cn.managame.jpa.core.write.AbstractBatchExecutor;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;

import java.util.List;

/**
 * RDB 合并通道批量落库器。
 * 将异步队列合并后的变更批量写入 RDB，SAVE 走 upsert（最终状态语义）。物理目标已在提交期路由好、
 * 由调度器以 {@link ExecutorContext} 传入，本类只做 op→落库 + 埋点（埋点在 {@link AbstractBatchExecutor}）。
 */
public class RdbBatchExecutor extends AbstractBatchExecutor<RdbEntityMetadata> {

    private final RdbExecutor executor;

    public RdbBatchExecutor(RdbEntityMetadata metadata, RdbExecutor executor, MetricsCollector metrics) {
        super(metadata, metrics);
        this.executor = executor;
    }

    @Override
    protected void batchSave(List<Object> entities, ExecutorContext ctx) {
        executor.batchUpsert(metadata, entities, ctx);
    }

    @Override
    protected void batchDelete(List<Object> ids, ExecutorContext ctx) {
        executor.batchDelete(metadata, ids, ctx);
    }
}
