package cn.managame.ai.internal;

import cn.managame.metrics.Counter;
import cn.managame.metrics.LatencyBuckets;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Metrics reported by {@link cn.managame.ai.AiTools}. Internal implementation, not public API. */
public final class AiToolMetrics {
    public record ToolMeters(Counter calls, Counter failures, Counter rejected) {}

    private final boolean enabled;
    private final Map<String, ToolMeters> byTool;
    private final Counter notFound;
    private final Counter invalidArgs;
    private final LatencyBuckets latency;
    private final List<Meter> meters = new ArrayList<>();

    public AiToolMetrics(Metrics metrics, Collection<String> toolNames) {
        this.enabled = metrics != Metrics.noop();
        this.notFound = metrics.counter("managame.ai.tool.not_found");
        this.invalidArgs = metrics.counter("managame.ai.tool.invalid_args");
        Map<String, ToolMeters> map = new HashMap<>(toolNames.size() * 2);
        for (String name : toolNames) {
            map.put(name, new ToolMeters(
                    metrics.counter("managame.ai.tool.calls", "tool", name),
                    metrics.counter("managame.ai.tool.failures", "tool", name),
                    metrics.counter("managame.ai.tool.rejected", "tool", name)));
        }
        this.byTool = Map.copyOf(map);
        this.latency = enabled ? new LatencyBuckets() : null;
        if (enabled) {
            meters.addAll(latency.report(metrics, "managame.ai.tool.latency"));
        }
    }

    public long startTimer() { return enabled ? System.nanoTime() : 0L; }

    public ToolMeters forTool(String name) { return byTool.get(name); }

    public void notFound() { notFound.increment(); }

    public void invalidArgs() { invalidArgs.increment(); }

    public void recordLatency(long startNanos) {
        if (enabled && startNanos != 0L) {
            latency.record(Math.max(0L, System.nanoTime() - startNanos));
        }
    }
}
