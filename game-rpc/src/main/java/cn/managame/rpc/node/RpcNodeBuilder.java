package cn.managame.rpc.node;

import cn.managame.rpc.call.RpcHandler;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;

/** Mutable, reusable builder; each build snapshots configuration without starting resources. */
public final class RpcNodeBuilder {
    int nodeId;
    SocketAddress address;
    RpcHandler handler;
    long callTimeout = 5000, handshakeTimeout = 5000, reconnectDelay = 1000;
    long reconnectRandomDelay = -1;
    long heartbeatInterval = 10000, heartbeatTimeout = 30000;
    int maxFrameSize = 4 * 1024 * 1024;

    RpcNodeBuilder() {}
    public RpcNodeBuilder nodeId(int id) { nodeId = id; return this; }
    public RpcNodeBuilder bindAddress(SocketAddress value) { address = Objects.requireNonNull(value); return this; }
    public RpcNodeBuilder handler(RpcHandler value) { handler = Objects.requireNonNull(value); return this; }
    public RpcNodeBuilder callTimeout(Duration value) { callTimeout = millis(value); return this; }
    public RpcNodeBuilder handshakeTimeout(Duration value) { handshakeTimeout = millis(value); return this; }
    public RpcNodeBuilder reconnectDelay(Duration value) { reconnectDelay = millis(value); return this; }
    public RpcNodeBuilder reconnectRandomDelay(Duration value) {
        Objects.requireNonNull(value);
        if (value.isNegative()) throw new IllegalArgumentException("reconnectRandomDelay must be nonnegative");
        reconnectRandomDelay = value.toMillis();
        if (reconnectRandomDelay > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("reconnectRandomDelay must fit nanoseconds");
        return this;
    }
    public RpcNodeBuilder heartbeatInterval(Duration value) { heartbeatInterval = millis(value); return this; }
    public RpcNodeBuilder heartbeatTimeout(Duration value) { heartbeatTimeout = millis(value); return this; }
    public RpcNodeBuilder maxFrameSize(int value) { maxFrameSize = value; return this; }
    public RpcNode build() {
        if (nodeId == 0) throw new IllegalArgumentException("nodeId must be nonzero");
        Objects.requireNonNull(address, "bindAddress");
        Objects.requireNonNull(handler, "handler");
        if (heartbeatTimeout <= heartbeatInterval)
            throw new IllegalArgumentException("heartbeatTimeout must exceed heartbeatInterval");
        if (maxFrameSize < 32) throw new IllegalArgumentException("maxFrameSize must be >= 32");
        long jitter = reconnectRandomDelay < 0 ? reconnectDelay / 4 : reconnectRandomDelay;
        if (reconnectDelay > Long.MAX_VALUE / 1_000_000 - jitter)
            throw new IllegalArgumentException("reconnect delay plus jitter must fit nanoseconds");
        return new RpcNode(this);
    }
    private static long millis(Duration value) {
        long result = Objects.requireNonNull(value).toMillis();
        if (result <= 0 || result > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("duration must be 1ms..Long.MAX_VALUE nanoseconds");
        return result;
    }
}

