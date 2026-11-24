package cn.managame.ai.internal;

import cn.managame.metrics.Counter;
import cn.managame.metrics.LatencyBuckets;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/** Pre-created meters for {@link cn.managame.ai.mcp.McpTools}; reads no clock under {@link Metrics#noop()}. Not API. */
public final class McpMetrics {
    private final Metrics metrics;
    private final String[] baseTags;
    private final boolean timed;
    private final Counter notFound;
    private final Counter invalidArgs;
    private final Counter overloaded;
    private final Counter closed;
    private final List<Meter> owned = new ArrayList<>();

    public McpMetrics(Metrics metrics, String serverName, DoubleSupplier inFlight) {
        this.metrics = metrics;
        this.baseTags = serverName == null || serverName.isEmpty() ? new String[0] : new String[]{"server", serverName};
        this.timed = metrics != Metrics.noop();
        this.notFound = metrics.counter("managame.ai.mcp.not_found", baseTags);
        this.invalidArgs = metrics.counter("managame.ai.mcp.invalid_args", baseTags);
        this.overloaded = metrics.counter("managame.ai.mcp.overloaded", baseTags);
        this.closed = metrics.counter("managame.ai.mcp.closed", baseTags);
        this.owned.add(metrics.gauge("managame.ai.mcp.in_flight", inFlight, baseTags));
    }

    public ToolMeters forTool(String toolName) {
        String[] toolTags = withTag(baseTags, "tool", toolName);
        LatencyBuckets latency = null;
        if (timed) {
            latency = new LatencyBuckets();
            owned.addAll(latency.report(metrics, "managame.ai.mcp.latency", toolTags));
        }
        return new ToolMeters(
                metrics.counter("managame.ai.mcp.calls", toolTags),
                metrics.counter("managame.ai.mcp.failures", toolTags),
                metrics.counter("managame.ai.mcp.rejected", toolTags),
                latency);
    }

    public long startTimer() { return timed ? System.nanoTime() : 0L; }

    public void notFound() { notFound.increment(); }

    public void invalidArgs() { invalidArgs.increment(); }

    public void overloaded() { overloaded.increment(); }

    public void closed() { closed.increment(); }

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

    public record ToolMeters(Counter calls, Counter failures, Counter rejected, LatencyBuckets latency) {
        public void recordLatency(long startNanos) {
            if (latency != null && startNanos != 0L) latency.record(System.nanoTime() - startNanos);
        }
    }
}
