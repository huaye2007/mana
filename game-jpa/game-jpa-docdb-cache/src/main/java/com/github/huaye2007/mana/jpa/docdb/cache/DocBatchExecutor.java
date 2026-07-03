package com.github.huaye2007.mana.jpa.docdb.cache;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.core.metrics.MetricsCollector;
import com.github.huaye2007.mana.jpa.core.write.AbstractBatchExecutor;
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;

import java.util.List;

/**
 * DocDB 合并通道批量落库器。
 * 将异步队列合并后的变更批量写入 DocDB，SAVE 走 save（最终状态语义）。物理目标已在提交期路由好、
 * 由调度器以 {@link ExecutorContext} 传入，本类只做 op→落库 + 埋点（埋点在 {@link AbstractBatchExecutor}）。
 */
public class DocBatchExecutor extends AbstractBatchExecutor<DocEntityMetadata> {

    private final DocExecutor executor;

    public DocBatchExecutor(DocEntityMetadata metadata, DocExecutor executor, MetricsCollector metrics) {
        super(metadata, metrics);
        this.executor = executor;
    }

    @Override
    protected void batchSave(List<Object> entities, ExecutorContext ctx) {
        executor.batchSave(metadata, entities, ctx);
    }

    @Override
    protected void batchDelete(List<Object> ids, ExecutorContext ctx) {
        executor.batchDelete(metadata, ids, ctx);
    }
}
