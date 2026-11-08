package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RPC_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.RPC_UNAVAILABLE;

import cn.managame.FrameworkErrorCodes;
import cn.managame.metrics.Counter;
import cn.managame.metrics.LatencyBuckets;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.network.connection.WriteStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** What one peer reports, tagged {@code node} with its id, its meters made once when the peer is. */
final class PeerMetrics {
    /** The failures counted, the framework's own; an application's codes make no series of their own. */
    private static final int[] FAILURES = {RPC_UNAVAILABLE, RPC_OVERLOADED, RPC_TIMEOUT, RPC_HANDLER_ERROR, RPC_PROTOCOL_ERROR};
    final Counter requestsReceived, notifiesReceived, handlerFailed, repliesLost, disconnects, restarts;
    private final Counter notifiesSent, notifiesBusy, notifiesInactive;
    private final Map<Integer, Counter> failures;
    private final List<Meter> meters = new ArrayList<>();
    /** Null with no backend, so that an answer reads no clock. */
    private final LatencyBuckets latency;

    PeerMetrics(Metrics metrics, int nodeId, LongSupplier callsSent, DoubleSupplier callsPending, DoubleSupplier connections, DoubleSupplier writeQueue) {
        String id = String.valueOf(nodeId);
        requestsReceived = metrics.counter("managame.rpc.requests.received", "node", id);
        notifiesReceived = metrics.counter("managame.rpc.notifies.received", "node", id);
        handlerFailed = metrics.counter("managame.rpc.handler.failed", "node", id);
        repliesLost = metrics.counter("managame.rpc.replies.lost", "node", id);
        disconnects = metrics.counter("managame.rpc.peer.disconnects", "node", id);
        restarts = metrics.counter("managame.rpc.peer.restarts", "node", id);
        notifiesSent = metrics.counter("managame.rpc.notifies.sent", "node", id);
        notifiesBusy = metrics.counter("managame.rpc.notifies.refused", "node", id, "status", WriteStatus.NOT_WRITABLE.name());
        notifiesInactive = metrics.counter("managame.rpc.notifies.refused", "node", id, "status", WriteStatus.INACTIVE.name());
        Map<Integer, Counter> counters = new HashMap<>();
        for (int code : FAILURES) counters.put(code, metrics.counter("managame.rpc.calls.failed", "node", id, "code", FrameworkErrorCodes.name(code)));
        failures = Map.copyOf(counters);
        latency = metrics == Metrics.noop() ? null : new LatencyBuckets();
        if (latency != null) meters.addAll(latency.report(metrics, "managame.rpc.calls.latency", "node", id));
        meters.add(metrics.counter("managame.rpc.calls.sent", callsSent, "node", id));
        meters.add(metrics.gauge("managame.rpc.calls.pending", callsPending, "node", id));
        meters.add(metrics.gauge("managame.rpc.peer.connections", connections, "node", id));
        meters.add(metrics.gauge("managame.rpc.peer.write_queue_bytes", writeQueue, "node", id));
    }

    /** A call made at {@code start}, on {@link System#nanoTime()}, was answered by the peer. */
    void answered(long start) {
        if (latency != null) latency.record(System.nanoTime() - start);
    }

    void failed(int code) {
        Counter counter = failures.get(code);
        if (counter != null) counter.increment();
    }

    void notified(WriteStatus status) {
        (status == WriteStatus.ACCEPTED ? notifiesSent : status == WriteStatus.NOT_WRITABLE ? notifiesBusy : notifiesInactive).increment();
    }

    void close() { meters.forEach(Meter::close); }
}
