package cn.managame.network.netty.connection;

/** Per-connection admission limits, including writes waiting for the EventLoop. */
public record OutboundWriteLimits(int maxPendingWrites, long maxPendingBytes) {
    public static final OutboundWriteLimits DEFAULT =
            new OutboundWriteLimits(1024, 8L * 1024 * 1024);

    public OutboundWriteLimits {
        if (maxPendingWrites <= 0 || maxPendingBytes <= 0) {
            throw new IllegalArgumentException("Outbound write limits must be positive");
        }
    }
}
