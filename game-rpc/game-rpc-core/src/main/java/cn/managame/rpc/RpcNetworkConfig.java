package cn.managame.rpc;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

/** Immutable transport configuration; contains no runtime or call state. */
public record RpcNetworkConfig(
        int nodeId,
        InetSocketAddress listenAddress,
        int maxMessageBytes,
        boolean consolidateFlush,
        Duration readIdleTimeout) {
    public RpcNetworkConfig {
        Objects.requireNonNull(readIdleTimeout, "readIdleTimeout");
        if (!readIdleTimeout.isZero()) RpcChecks.timeoutNanos(readIdleTimeout);
    }

    public RpcNetworkConfig(
            int nodeId,
            InetSocketAddress listenAddress,
            int maxMessageBytes,
            boolean consolidateFlush) {
        this(nodeId, listenAddress, maxMessageBytes, consolidateFlush, Duration.ZERO);
    }
}
