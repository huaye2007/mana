package cn.managame.ai.internal;

import cn.managame.FrameworkErrorCodes;
import cn.managame.metrics.Counter;
import cn.managame.metrics.LatencyBuckets;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/** Metrics reported by {@link cn.managame.ai.AiClient}. Internal implementation, not public API. */
public final class AiClientMetrics {
    private final Metrics metrics;
    private final boolean enabled;
    private final String[] baseTags;
    private final Counter unaryCalls;
    private final Counter streamCalls;
    private final Counter notifyCalls;
    private final Counter streamChunks;
    private final ConcurrentHashMap<Integer, Counter> failures = new ConcurrentHashMap<>();
    private final LatencyBuckets latency;
    private final List<Meter> meters = new ArrayList<>();

    public AiClientMetrics(Metrics metrics, String clientName, IntSupplier inFlight) {
        this.metrics = metrics;
        this.enabled = metrics != Metrics.noop();
        this.baseTags = clientName == null ? new String[0] : new String[]{"client", clientName};
        this.unaryCalls = metrics.counter("managame.ai.client.calls", withTag("kind", "call"));
        this.streamCalls = metrics.counter("managame.ai.client.calls", withTag("kind", "stream"));
        this.notifyCalls = metrics.counter("managame.ai.client.calls", withTag("kind", "notify"));
        this.streamChunks = metrics.counter("managame.ai.client.stream_chunks", baseTags);
        this.latency = enabled ? new LatencyBuckets() : null;
        if (enabled) {
            meters.add(metrics.gauge("managame.ai.client.in_flight", inFlight::getAsInt, baseTags));
            meters.addAll(latency.report(metrics, "managame.ai.client.latency", baseTags));
        }
    }

    public long startTimer() { return enabled ? System.nanoTime() : 0L; }

    public void unaryStarted() { unaryCalls.increment(); }
    public void streamStarted() { streamCalls.increment(); }
    public void notifyStarted() { notifyCalls.increment(); }
    public void chunkReceived() { streamChunks.increment(); }

    public void finished(long startNanos, int errorCode) {
        if (errorCode != FrameworkErrorCodes.SUCCESS) {
            failureCounter(errorCode).increment();
        }
        if (enabled && startNanos != 0L) {
            latency.record(Math.max(0L, System.nanoTime() - startNanos));
        }
    }

    public void close() {
        for (Meter meter : meters) meter.close();
        meters.clear();
    }

    private Counter failureCounter(int code) {
        return failures.computeIfAbsent(code, c ->
                metrics.counter("managame.ai.client.failures", withTag("code", FrameworkErrorCodes.name(c))));
    }

    private String[] withTag(String key, String value) {
        String[] tags = new String[baseTags.length + 2];
        System.arraycopy(baseTags, 0, tags, 0, baseTags.length);
        tags[baseTags.length] = key;
        tags[baseTags.length + 1] = value;
        return tags;
    }
}
