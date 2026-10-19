package cn.managame.boot.metrics;

import cn.managame.core.metrics.MetricsSource;
import cn.managame.data.GameData;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.node.RpcNode;
import cn.managame.runtime.GameRuntime;

/**
 * Adapts each component's snapshot record to a {@link MetricsSource}. The adaptation lives here, in the
 * composition module, for two reasons: a component stays free of the metrics contract, and game-network
 * keeps its property of depending on no other framework module — it publishes a record, and this class,
 * which already depends on everything, maps it.
 *
 * <p>Every source does the same thing: take one snapshot and report its fields. Names are the record's
 * component names in dotted form, under the prefix the registry was given, so {@code runtime} plus
 * {@code route.queued} reads as {@code runtime.route.queued}.
 */
public final class FrameworkMetrics {
    private FrameworkMetrics() {}

    public static MetricsSource of(GameRuntime runtime) {
        return sink -> {
            var s = runtime.stats();
            sink.gauge("accepting", s.accepting() ? 1 : 0);
            sink.gauge("route.inflight", s.inFlight());
            sink.gauge("route.queued", s.queued());
            sink.gauge("route.running", s.running());
            // What a drain that will not finish is waiting for, and how long it has waited.
            sink.gauge("continuations", s.continuations());
            sink.gauge("continuations.oldest.nanos", s.oldestContinuationNanos());
            // Which Route a stuck instance is stuck on. The Key is a value here rather than a dimension,
            // so watching it costs one series however many Routes exist.
            sink.gauge("running.oldest.nanos", s.oldestRunning().runningNanos());
            sink.gauge("running.oldest.domain", s.oldestRunning().routeDomain());
            sink.gauge("running.oldest.key", s.oldestRunning().routeKey());
            // Per Domain, so "the instance is shedding" becomes "this Domain is". The sink carries no
            // dimensions, so the Domain is part of the name; its cardinality is fixed at build.
            for (var domain : s.domains()) {
                // A name segment must start with a letter, so the Domain id follows one rather than standing alone.
                String prefix = "domain" + domain.routeDomain() + ".";
                sink.gauge(prefix + "queued", domain.queued());
                sink.gauge(prefix + "running", domain.running());
                sink.counter(prefix + "completed", domain.completed());
                sink.counter(prefix + "rejected", domain.rejected());
                // The denominator queued is judged against; 0 means the executor reports no bound.
                sink.gauge(prefix + "executor.capacity", domain.executor().capacity());
                sink.gauge(prefix + "executor.route.capacity", domain.executor().routeCapacity());
                sink.gauge(prefix + "executor.active.routes", domain.executor().activeRoutes());
                // The Domain is already in the prefix, so only the Key and the age are worth repeating.
                sink.gauge(prefix + "running.oldest.nanos", domain.oldestRunning().runningNanos());
                sink.gauge(prefix + "running.oldest.key", domain.oldestRunning().routeKey());
            }
            sink.counter("route.completed", s.completed());
            sink.counter("route.rejected", s.rejected());
            sink.counter("route.errors", s.errors());
            sink.counter("route.execution.nanos", s.executionNanos());
        };
    }

    public static MetricsSource of(GameData data) {
        return sink -> {
            var s = data.stats();
            sink.gauge("accepting", s.accepting() ? 1 : 0);
            sink.gauge("pending.changes", s.pendingChanges());
            sink.gauge("queued.logs", s.queuedLogs());
            sink.gauge("cache.single.entries", s.singleCacheEntries());
            sink.gauge("cache.group.entries", s.groupCacheEntries());
            sink.counter("flushes", s.flushes());
            sink.counter("failed.batches", s.failedBatches());
            sink.counter("dropped.logs", s.droppedLogs());
            sink.counter("changes.rejected", s.rejectedChanges());
            sink.counter("save.nanos", s.saveNanos());
        };
    }

    public static MetricsSource of(RpcNode node) {
        return sink -> {
            var s = node.stats();
            sink.gauge("peers", s.peers());
            sink.gauge("peers.connected", s.peersConnected());
            sink.gauge("connections", s.connections());
            sink.gauge("calls.pending", s.pendingCalls());
            sink.counter("calls.rejected", s.callsRejected());
            sink.counter("calls.timedout", s.callsTimedOut());
            sink.counter("sends.refused", s.sendsRefused());
            sink.counter("handshakes.accepted", s.handshakesAccepted());
            sink.counter("reconnects", s.reconnects());
        };
    }

    public static MetricsSource of(NetworkServer server) {
        return sink -> {
            var s = server.stats();
            sink.gauge("connections", s.liveConnections());
            sink.counter("accepted", s.accepted());
            sink.counter("established", s.established());
            sink.counter("closed", s.closed());
            sink.counter("establishment.failures", s.establishmentFailures());
            sink.counter("writes.inactive", s.writesInactive());
            sink.counter("writes.notwritable", s.writesNotWritable());
            sink.counter("handler.exceptions", s.handlerExceptions());
        };
    }
}
