package cn.managame.network.netty;

import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;

/**
 * What belongs to no one listener and costs nothing until the backend collects: how far behind EventLoops are and how
 * much direct memory Netty uses. EventLoops are shared or the application's, so it binds them itself, once each:
 *
 * <pre>{@code
 * NetworkServer server = NetworkServer.start(9001, game);
 * Meter loops = NetworkMetrics.eventLoops(metrics, server.eventLoops(), "game");
 * Meter memory = NetworkMetrics.directMemory(metrics);
 * }</pre>
 */
public final class NetworkMetrics {
    private NetworkMetrics() {}

    /**
     * Reports {@code managame.network.eventloop.pending_tasks}, tagged {@code group} with {@code name}: the tasks waiting
     * on the EventLoop of {@code group} furthest behind. Close the meter when the group goes away.
     */
    public static Meter eventLoops(Metrics metrics, EventLoopGroup group, String name) {
        return metrics.gauge("managame.network.eventloop.pending_tasks", () -> mostPending(group), "group", name);
    }

    /** Reports {@code managame.network.memory.direct_used}: the bytes of direct memory Netty's default allocator uses. */
    public static Meter directMemory(Metrics metrics) {
        ByteBufAllocatorMetric allocator = ((ByteBufAllocatorMetricProvider) ByteBufAllocator.DEFAULT).metric();
        return metrics.gauge("managame.network.memory.direct_used", allocator::usedDirectMemory);
    }

    private static int mostPending(EventLoopGroup group) {
        int most = 0;
        for (EventExecutor loop : group)
            if (loop instanceof SingleThreadEventExecutor single) most = Math.max(most, single.pendingTasks());
        return most;
    }
}
