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
    private final boolean timed;
    private final Counter notFound;
    private final Counter invalidArgs;
    private final Counter overloaded;
    private final LatencyBuckets latency;
    private final List<Meter> owned = new ArrayList<>();

    public McpMetrics(Metrics metrics, DoubleSupplier inFlight) {
        this.metrics = metrics;
        this.timed = metrics != Metrics.noop();
        this.notFound = metrics.counter("managame.ai.mcp.not_found");
        this.invalidArgs = metrics.counter("managame.ai.mcp.invalid_args");
        this.overloaded = metrics.counter("managame.ai.mcp.overloaded");
        this.owned.add(metrics.gauge("managame.ai.mcp.in_flight", inFlight));
        if (timed) {
            this.latency = new LatencyBuckets();
            this.owned.addAll(this.latency.report(metrics, "managame.ai.mcp.latency"));
        } else {
            this.latency = null;
        }
    }

    public ToolMeters forTool(String toolName) {
        return new ToolMeters(
                metrics.counter("managame.ai.mcp.calls", "tool", toolName),
                metrics.counter("managame.ai.mcp.failures", "tool", toolName),
                metrics.counter("managame.ai.mcp.rejected", "tool", toolName));
    }

    public long startTimer() { return timed ? System.nanoTime() : 0L; }

    public void recordLatency(long startNanos) {
        if (timed && startNanos != 0L) latency.record(System.nanoTime() - startNanos);
    }

    public void notFound() { notFound.increment(); }

    public void invalidArgs() { invalidArgs.increment(); }

    public void overloaded() { overloaded.increment(); }

    public void close() {
        for (Meter meter : owned) meter.close();
    }

    public record ToolMeters(Counter calls, Counter failures, Counter rejected) {}
}
