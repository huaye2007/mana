package cn.managame.spring.metrics;

import cn.managame.core.metrics.MetricsSource;
import cn.managame.data.GameData;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.node.RpcNode;
import cn.managame.runtime.GameRuntime;

/**
 * Adapts each component's snapshot record to a {@link MetricsSource}. The adaptation lives here, in the
 * integration module, for two reasons: a component stays free of the metrics contract, and game-network
 * keeps its property of depending on no other framework module — it publishes a record, and this class,
 * which already depends on everything, maps it.
 *
 * <p>Every source does the same thing: take one snapshot and report its fields. Names are the record's
 * component names in dotted form, under the prefix the registry was given, so {@code runtime} plus
 * {@code route.queued} reads as {@code runtime.route.queued}.
 */
final class FrameworkMetrics {
    private FrameworkMetrics() {}

    static MetricsSource of(GameRuntime runtime) {
        return sink -> {
            var s = runtime.stats();
            sink.gauge("accepting", s.accepting() ? 1 : 0);
            sink.gauge("route.inflight", s.inFlight());
            sink.gauge("route.queued", s.queued());
            sink.gauge("route.running", s.running());
            sink.counter("route.completed", s.completed());
            sink.counter("route.rejected", s.rejected());
            sink.counter("route.errors", s.errors());
            sink.counter("route.execution.nanos", s.executionNanos());
        };
    }

    static MetricsSource of(GameData data) {
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

    static MetricsSource of(RpcNode node) {
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

    static MetricsSource of(NetworkServer server) {
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
