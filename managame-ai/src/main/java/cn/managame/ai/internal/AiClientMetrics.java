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
    private final LatencyBuckets latency;
    private final List<Meter> owned = new ArrayList<>();

    public AiClientMetrics(Metrics metrics, String clientName, DoubleSupplier inFlight) {
        timed = metrics != Metrics.noop();
        String[] base = clientName == null || clientName.isEmpty() ? new String[0] : new String[]{"client", clientName};
        unaryCalls = metrics.counter("managame.ai.client.calls", withTag(base, "kind", "call"));
        streamCalls = metrics.counter("managame.ai.client.calls", withTag(base, "kind", "stream"));
        notifyCalls = metrics.counter("managame.ai.client.calls", withTag(base, "kind", "notify"));
        streamEvents = metrics.counter("managame.ai.client.stream_events", base);
        for (int i = 0; i < FAILURE_CODES.length; i++) {
            failures[i] = metrics.counter("managame.ai.client.failures",
                    withTag(base, "code", FrameworkErrorCodes.name(FAILURE_CODES[i])));
        }
        otherFailures = metrics.counter("managame.ai.client.failures", withTag(base, "code", "OTHER"));
        owned.add(metrics.gauge("managame.ai.client.in_flight", inFlight, base));
        if (timed) {
            latency = new LatencyBuckets();
            owned.addAll(latency.report(metrics, "managame.ai.client.latency", base));
        } else {
            latency = null;
        }
    }

    public long startTimer() { return timed ? System.nanoTime() : 0L; }

    public void recordLatency(long startNanos) {
        if (timed && startNanos != 0L) latency.record(System.nanoTime() - startNanos);
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
