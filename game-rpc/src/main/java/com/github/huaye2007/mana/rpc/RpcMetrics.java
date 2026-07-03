package com.github.huaye2007.mana.rpc;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 客户端运行指标：LongAdder 计数 + 固定桶时延直方图，热路径写入无锁无分配。
 * 框架只负责累加，宿主接入监控系统时周期性读取各 getter 上报（计数单调递增，速率由采集端做差）。
 */
public final class RpcMetrics {

    /** 时延桶上界（毫秒），最后一桶为 > 最大上界 */
    public static final long[] LATENCY_BUCKET_UPPER_MILLIS = {1, 10, 50, 100, 500, 1000};

    private final LongAdder[] latencyBuckets;
    private final LongAccumulator maxLatencyNanos = new LongAccumulator(Long::max, 0L);
    private final LongAdder requestsSent = new LongAdder();
    private final LongAdder onewaysSent = new LongAdder();
    private final LongAdder responsesOk = new LongAdder();
    private final LongAdder responsesError = new LongAdder();
    private final LongAdder timeouts = new LongAdder();
    private final LongAdder writeFailures = new LongAdder();
    private final LongAdder rejectedNoConnection = new LongAdder();
    private final LongAdder rejectedNotWritable = new LongAdder();
    private final LongAdder lateResponses = new LongAdder();
    private final LongAdder connectionsClosed = new LongAdder();
    private final LongAdder reconnectAttempts = new LongAdder();
    private final LongAdder reconnectSuccesses = new LongAdder();
    private final LongAdder rejectedPendingLimit = new LongAdder();

    public RpcMetrics() {
        latencyBuckets = new LongAdder[LATENCY_BUCKET_UPPER_MILLIS.length + 1];
        for (int i = 0; i < latencyBuckets.length; i++) {
            latencyBuckets[i] = new LongAdder();
        }
    }

    /** 响应到达时记录本次调用耗时（请求发出到响应关联，IO 线程侧，不含业务线程排队） */
    public void onResponseLatency(long nanos) {
        long millis = nanos / 1_000_000;
        int bucket = 0;
        while (bucket < LATENCY_BUCKET_UPPER_MILLIS.length && millis > LATENCY_BUCKET_UPPER_MILLIS[bucket]) {
            bucket++;
        }
        latencyBuckets[bucket].increment();
        maxLatencyNanos.accumulate(nanos);
    }

    /**
     * 各时延桶计数，下标 i 对应 ≤ LATENCY_BUCKET_UPPER_MILLIS[i]，最后一个为超出最大上界。
     */
    public long[] responseLatencyBuckets() {
        long[] result = new long[latencyBuckets.length];
        for (int i = 0; i < latencyBuckets.length; i++) {
            result[i] = latencyBuckets[i].sum();
        }
        return result;
    }

    public long maxResponseLatencyNanos() {
        return maxLatencyNanos.get();
    }

    public void onRejectedPendingLimit() {
        rejectedPendingLimit.increment();
    }

    public long rejectedPendingLimit() {
        return rejectedPendingLimit.sum();
    }

    public void onRequestSent() {
        requestsSent.increment();
    }

    public void onOnewaySent() {
        onewaysSent.increment();
    }

    public void onResponseOk() {
        responsesOk.increment();
    }

    public void onResponseError() {
        responsesError.increment();
    }

    public void onTimeout() {
        timeouts.increment();
    }

    public void onWriteFailure() {
        writeFailures.increment();
    }

    public void onRejectedNoConnection() {
        rejectedNoConnection.increment();
    }

    public void onRejectedNotWritable() {
        rejectedNotWritable.increment();
    }

    public void onLateResponse() {
        lateResponses.increment();
    }

    public void onConnectionClosed() {
        connectionsClosed.increment();
    }

    public void onReconnectAttempt() {
        reconnectAttempts.increment();
    }

    public void onReconnectSuccess() {
        reconnectSuccesses.increment();
    }

    public long requestsSent() {
        return requestsSent.sum();
    }

    public long onewaysSent() {
        return onewaysSent.sum();
    }

    public long responsesOk() {
        return responsesOk.sum();
    }

    public long responsesError() {
        return responsesError.sum();
    }

    public long timeouts() {
        return timeouts.sum();
    }

    public long writeFailures() {
        return writeFailures.sum();
    }

    public long rejectedNoConnection() {
        return rejectedNoConnection.sum();
    }

    public long rejectedNotWritable() {
        return rejectedNotWritable.sum();
    }

    public long lateResponses() {
        return lateResponses.sum();
    }

    public long connectionsClosed() {
        return connectionsClosed.sum();
    }

    public long reconnectAttempts() {
        return reconnectAttempts.sum();
    }

    public long reconnectSuccesses() {
        return reconnectSuccesses.sum();
    }

    @Override
    public String toString() {
        return "RpcMetrics{requestsSent=" + requestsSent()
                + ", onewaysSent=" + onewaysSent()
                + ", responsesOk=" + responsesOk()
                + ", responsesError=" + responsesError()
                + ", timeouts=" + timeouts()
                + ", writeFailures=" + writeFailures()
                + ", rejectedNoConnection=" + rejectedNoConnection()
                + ", rejectedNotWritable=" + rejectedNotWritable()
                + ", lateResponses=" + lateResponses()
                + ", connectionsClosed=" + connectionsClosed()
                + ", reconnectAttempts=" + reconnectAttempts()
                + ", reconnectSuccesses=" + reconnectSuccesses()
                + ", rejectedPendingLimit=" + rejectedPendingLimit()
                + ", maxLatencyMillis=" + maxResponseLatencyNanos() / 1_000_000 + "}";
    }
}
