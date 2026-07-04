package cn.managame.jpa.core.metrics;

import java.util.function.Supplier;

/**
 * 监控埋点 SPI。
 * 实现层可接入 Prometheus、Micrometer 等监控系统。
 */
public interface MetricsCollector {

    /**
     * 记录操作耗时
     */
    void recordLatency(String operation, String entity, long millis);

    /**
     * 记录操作次数
     */
    void recordCount(String operation, String entity, int count);

    /**
     * 记录错误
     */
    void recordError(String operation, String entity, Throwable error);

    /**
     * Record the latest value of a runtime gauge such as queue depth.
     */
    default void recordGauge(String metric, String entity, long value) {}

    /**
     * 包裹一次有返回值的操作：成功记录耗时，异常记录错误后原样抛出。
     * 用于消除仓储层「start/try/recordLatency/catch/recordError/throw」的重复样板。
     */
    default <R> R instrument(String operation, String entity, Supplier<R> action) {
        long start = System.currentTimeMillis();
        try {
            R result = action.get();
            recordLatency(operation, entity, System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            recordError(operation, entity, e);
            throw e;
        }
    }

    /**
     * 包裹一次无返回值的操作。语义同 {@link #instrument(String, String, Supplier)}。
     */
    default void instrument(String operation, String entity, Runnable action) {
        instrument(operation, entity, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 空实现（默认不采集）
     */
    MetricsCollector NOOP = new MetricsCollector() {
        @Override
        public void recordLatency(String operation, String entity, long millis) {}

        @Override
        public void recordCount(String operation, String entity, int count) {}

        @Override
        public void recordError(String operation, String entity, Throwable error) {}

        @Override
        public void recordGauge(String metric, String entity, long value) {}
    };
}
