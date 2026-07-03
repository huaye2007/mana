package com.github.huaye2007.mana.jpa.core.write;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;

/**
 * 物理写入目标：一条写任务最终落到哪个数据源的哪张物理表。
 * <p>
 * 提交期由 {@link WriteRouter} 解析得出，作为异步缓冲对象的分桶 key。只携带
 * {@code (dataSource, physicalTable)}，<b>不</b>携带 routingKey——同一物理表内不同行
 * 的 routingKey 可能不同，但批量写时执行层只用 dataSource 选库、physicalTable 选表
 * （见 MySQL/Mongo 执行器），routingKey 不参与，因此作为分桶 key 必须剔除以保证
 * 路由到同一物理表的行落进同一个缓冲对象。
 *
 * @param dataSource    数据源名（不分库时为 {@code "default"}）
 * @param physicalTable 物理表名/集合名（不分表时为 {@code null}，由执行层回退到逻辑名）
 */
public record WriteDestination(String dataSource, String physicalTable) {

    /** 默认数据源名，与 {@link ExecutorContext.DefaultContext} 保持一致。 */
    public static final String DEFAULT_DATA_SOURCE = "default";

    /** 不分库不分表的默认目标。 */
    public static final WriteDestination DEFAULT = new WriteDestination(DEFAULT_DATA_SOURCE, null);

    public WriteDestination {
        if (dataSource == null || dataSource.isEmpty()) {
            dataSource = DEFAULT_DATA_SOURCE;
        }
    }

    /**
     * 归一化构造：默认库且无物理表名时返回 {@link #DEFAULT} 单例，避免缓冲对象冗余分桶。
     */
    public static WriteDestination of(String dataSource, String physicalTable) {
        if ((dataSource == null || dataSource.isEmpty() || DEFAULT_DATA_SOURCE.equals(dataSource))
                && physicalTable == null) {
            return DEFAULT;
        }
        return new WriteDestination(dataSource, physicalTable);
    }

    /**
     * 转为执行上下文，供批量执行器选库选表。每个缓冲对象创建时调用一次并缓存，
     * routingKey 置 {@code null}（批量写层不使用）。
     */
    public ExecutorContext toContext() {
        if (this.equals(DEFAULT)) {
            return ExecutorContext.defaultContext();
        }
        return ExecutorContext.of(dataSource, null, physicalTable);
    }
}
