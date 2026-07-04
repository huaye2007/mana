package cn.managame.jpa.core.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 默认监控采集器（内存统计）。
 * 生产环境可替换为 Prometheus / Micrometer 实现。
 */
public class DefaultMetricsCollector implements MetricsCollector {

    private final Map<String, LongAdder> invocationCounters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> itemCounters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> totalLatency = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Long> gauges = new ConcurrentHashMap<>();

    @Override
    public void recordLatency(String operation, String entity, long millis) {
        String key = operation + ":" + entity;
        totalLatency.computeIfAbsent(key, k -> new LongAdder()).add(millis);
        invocationCounters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    @Override
    public void recordCount(String operation, String entity, int count) {
        String key = operation + ":" + entity;
        itemCounters.computeIfAbsent(key, k -> new LongAdder()).add(count);
    }

    @Override
    public void recordError(String operation, String entity, Throwable error) {
        String key = operation + ":" + entity;
        errorCounters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    @Override
    public void recordGauge(String metric, String entity, long value) {
        gauges.put(metric + ":" + entity, value);
    }

    /**
     * 获取显式记录的业务条数；如果没有显式条数，则返回耗时采样的调用次数。
     */
    public long getCount(String operation, String entity) {
        long itemCount = getItemCount(operation, entity);
        return itemCount != 0 ? itemCount : getInvocationCount(operation, entity);
    }

    /**
     * 获取耗时采样次数，通常等于操作调用次数。
     */
    public long getInvocationCount(String operation, String entity) {
        LongAdder adder = invocationCounters.get(operation + ":" + entity);
        return adder != null ? adder.sum() : 0;
    }

    /**
     * 获取通过 recordCount 显式记录的业务条数。
     */
    public long getItemCount(String operation, String entity) {
        LongAdder adder = itemCounters.get(operation + ":" + entity);
        return adder != null ? adder.sum() : 0;
    }

    /**
     * 获取操作总耗时
     */
    public long getTotalLatency(String operation, String entity) {
        LongAdder adder = totalLatency.get(operation + ":" + entity);
        return adder != null ? adder.sum() : 0;
    }

    /**
     * 获取错误次数
     */
    public long getErrorCount(String operation, String entity) {
        LongAdder adder = errorCounters.get(operation + ":" + entity);
        return adder != null ? adder.sum() : 0;
    }

    public long getGauge(String metric, String entity) {
        return gauges.getOrDefault(metric + ":" + entity, 0L);
    }
}
