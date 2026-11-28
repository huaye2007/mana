package cn.managame.ai.internal;

import static cn.managame.FrameworkErrorCodes.AI_CANCELLED;
import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_UNAVAILABLE;

import cn.managame.FrameworkErrorCodes;
import cn.managame.metrics.Counter;
import cn.managame.metrics.LatencyBuckets;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/** Pre-created meters for {@link cn.managame.ai.AiClient}; reads no clock under {@link Metrics#noop()}. Not API. */
public final class AiClientMetrics {
    private static final int[] FAILURE_CODES = {
            AI_CLOSED, AI_UNAVAILABLE, AI_TIMEOUT, AI_OVERLOADED, AI_CANCELLED, AI_PROTOCOL_ERROR
    };
    private final boolean timed;
    private final Counter unaryCalls, streamCalls, notifyCalls, streamEvents;
    private final Counter[] failures = new Counter[FAILURE_CODES.length];
    private final Counter otherFailures;
    private final LatencyBuckets callLatency, streamLatency, notifyLatency;
    private final List<Meter> owned = new ArrayList<>();

    public AiClientMetrics(Metrics metrics, String clientName, DoubleSupplier inFlight, DoubleSupplier notifyInFlight) {
        timed = metrics != Metrics.noop();
        String[] base = clientName == null || clientName.isEmpty() ? new String[0] : new String[]{"client", clientName};
        String[] callTags = withTag(base, "kind", "call");
        String[] streamTags = withTag(base, "kind", "stream");
        String[] notifyTags = withTag(base, "kind", "notify");
        unaryCalls = metrics.counter("managame.ai.client.calls", callTags);
        streamCalls = metrics.counter("managame.ai.client.calls", streamTags);
        notifyCalls = metrics.counter("managame.ai.client.calls", notifyTags);
        streamEvents = metrics.counter("managame.ai.client.stream_events", base);
        for (int i = 0; i < FAILURE_CODES.length; i++) {
            failures[i] = metrics.counter("managame.ai.client.failures",
                    withTag(base, "code", FrameworkErrorCodes.name(FAILURE_CODES[i])));
        }
        otherFailures = metrics.counter("managame.ai.client.failures", withTag(base, "code", "OTHER"));
        owned.add(metrics.gauge("managame.ai.client.in_flight", inFlight, base));
        owned.add(metrics.gauge("managame.ai.client.notify_in_flight", notifyInFlight, base));
        if (timed) {
            callLatency = new LatencyBuckets();
            streamLatency = new LatencyBuckets();
            notifyLatency = new LatencyBuckets();
            owned.addAll(callLatency.report(metrics, "managame.ai.client.latency", callTags));
            owned.addAll(streamLatency.report(metrics, "managame.ai.client.latency", streamTags));
            owned.addAll(notifyLatency.report(metrics, "managame.ai.client.latency", notifyTags));
        } else {
            callLatency = null;
            streamLatency = null;
            notifyLatency = null;
        }
    }

    public long startTimer() { return timed ? System.nanoTime() : 0L; }

    public void recordCallLatency(long startNanos) {
        if (timed && startNanos != 0L) callLatency.record(System.nanoTime() - startNanos);
    }

    public void recordStreamLatency(long startNanos) {
        if (timed && startNanos != 0L) streamLatency.record(System.nanoTime() - startNanos);
    }

    public void recordNotifyLatency(long startNanos) {
        if (timed && startNanos != 0L) notifyLatency.record(System.nanoTime() - startNanos);
    }

    public void callStarted() { unaryCalls.increment(); }

    public void streamStarted() { streamCalls.increment(); }

    public void notifyStarted() { notifyCalls.increment(); }

    public void streamEvent() { streamEvents.increment(); }

    public void failed(int errorCode) {
        for (int i = 0; i < FAILURE_CODES.length; i++) {
            if (FAILURE_CODES[i] == errorCode) {
                failures[i].increment();
                return;
            }
        }
        otherFailures.increment();
    }

    public void close() {
        for (Meter meter : owned) meter.close();
    }

    private static String[] withTag(String[] base, String key, String value) {
        String[] tags = new String[base.length + 2];
        System.arraycopy(base, 0, tags, 0, base.length);
        tags[base.length] = key;
        tags[base.length + 1] = value;
        return tags;
    }
}
