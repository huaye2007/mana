package cn.managame.rpc;

import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.network.netty.NetworkMetrics;
import cn.managame.rpc.Refusal.Reason;
import io.netty.channel.EventLoopGroup;
import io.netty.util.HashedWheelTimer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What a node reports about itself rather than about one peer, its meters made once at build. */
final class NodeMetrics {
    final Counter heartbeatsLost, bytesRead, bytesWritten;
    private final Counter[] refused = new Counter[Reason.values().length];
    private final Metrics metrics;
    private final List<Meter> meters = new ArrayList<>();

    NodeMetrics(Metrics metrics, HashedWheelTimer timer) {
        this.metrics = metrics;
        heartbeatsLost = metrics.counter("managame.rpc.connections.heartbeat_lost");
        bytesRead = metrics.counter("managame.rpc.bytes.read");
        bytesWritten = metrics.counter("managame.rpc.bytes.written");
        for (Reason reason : Reason.values())
            refused[reason.ordinal()] = metrics.counter("managame.rpc.handshakes.refused", "reason", reason.name().toLowerCase(Locale.ROOT));
        meters.add(metrics.gauge("managame.rpc.timer.pending", timer::pendingTimeouts));
    }

    /** Also reports how far behind {@code group} is, until the node closes. */
    void eventLoops(EventLoopGroup group, String name) { meters.add(NetworkMetrics.eventLoops(metrics, group, name)); }

    void refused(Reason reason) { refused[reason.ordinal()].increment(); }

    void close() { meters.forEach(Meter::close); }
}
