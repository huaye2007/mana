package com.github.huaye2007.mana.jpa.core.executor;

/**
 * 执行上下文，携带路由、数据源等运行时信息。
 */
public interface ExecutorContext {

    /**
     * 数据源名称
     */
    String dataSourceName();

    /**
     * 路由键（用于分片路由）
     */
    Object routingKey();

    /**
     * 物理表名/集合名（分表时由路由策略计算得出，为 null 时使用元数据中的逻辑名）
     */
    default String physicalTableName() {
        return null;
    }

    /**
     * 默认上下文（无路由、默认数据源）
     */
    static ExecutorContext defaultContext() {
        return DefaultContext.INSTANCE;
    }

    /**
     * 创建携带路由信息的上下文。
     *
     * @param dataSourceName    数据源名称（不分库时传 "default"）
     * @param routingKey        路由键
     * @param physicalTableName 物理表名（分表后的实际表名）
     */
    static ExecutorContext of(String dataSourceName, Object routingKey, String physicalTableName) {
        return new RoutedContext(dataSourceName, routingKey, physicalTableName);
    }

    /**
     * 默认上下文实现（单例）
     */
    final class DefaultContext implements ExecutorContext {
        static final DefaultContext INSTANCE = new DefaultContext();

        @Override
        public String dataSourceName() { return "default"; }

        @Override
        public Object routingKey() { return null; }

        @Override
        public String physicalTableName() { return null; }

        @Override
        public String toString() { return "ExecutorContext[default]"; }
    }

    /**
     * 携带路由信息的上下文实现
     */
    record RoutedContext(String dataSourceName, Object routingKey, String physicalTableName)
            implements ExecutorContext {
        @Override
        public String toString() {
            return "ExecutorContext[ds=" + dataSourceName + ", routingKey=" + routingKey
                    + ", physical=" + physicalTableName + "]";
        }
    }
}
