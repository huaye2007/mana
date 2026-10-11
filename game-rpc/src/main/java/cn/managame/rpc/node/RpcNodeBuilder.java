package cn.managame.rpc.node;

import cn.managame.rpc.call.RpcHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.WriteBufferWaterMark;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Mutable, reusable builder; each build snapshots configuration without starting resources. */
public final class RpcNodeBuilder {
    int nodeId;
    SocketAddress address;
    RpcHandler handler;
    long callTimeout = 5000, handshakeTimeout = 5000, reconnectDelay = 1000;
    long reconnectRandomDelay = -1;
    long heartbeatInterval = 10000, heartbeatTimeout = 30000;
    int maxFrameSize = 4 * 1024 * 1024;
    final Map<ChannelOption<?>, Object> channelOptions = new LinkedHashMap<>();
    final List<Consumer<ChannelPipeline>> transports = new ArrayList<>();
    byte[] handshakeSecret;
    long handshakeClockSkew = 60_000;

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
    /**
     * Per-connection outbound buffer bounds. A Slot stops accepting sends above {@code high} until the
     * buffer drains below {@code low}; sends then fail fast as UNAVAILABLE instead of growing memory.
     * Netty's default (32 KiB / 64 KiB) applies when unset.
     */
    public RpcNodeBuilder writeBufferWaterMark(int low, int high) {
        return channelOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(low, high));
    }
    /** Socket option for every RPC connection, inbound and outbound (for example TCP_NODELAY, SO_SNDBUF). */
    public <T> RpcNodeBuilder channelOption(ChannelOption<T> option, T value) {
        Objects.requireNonNull(option).validate(Objects.requireNonNull(value));
        channelOptions.put(option, value); return this;
    }
    /**
     * Byte-level handlers installed before RPC framing on every connection, in call order: TLS
     * (SslHandler via addFirst), FlushConsolidationHandler, traffic shaping, logging. RPC adds its
     * frame decoder and heartbeat handler after them; do not decode or consume RPC frames here.
     */
    public RpcNodeBuilder transport(Consumer<ChannelPipeline> configurer) { transports.add(Objects.requireNonNull(configurer)); return this; }
    /**
     * Shared cluster secret (at least 16 bytes). Every handshake then carries an HMAC-SHA256 proof, a
     * timestamp within {@code clockSkew} and a fresh nonce; the reply echoes the initiator's nonce. All
     * nodes of one cluster must use the same secret: a node without one accepts only unauthenticated peers.
     * This authenticates connection establishment only; use transport(...) TLS for confidentiality.
     */
    public RpcNodeBuilder handshakeSecret(byte[] secret, Duration clockSkew) {
        if (Objects.requireNonNull(secret).length < 16) throw new IllegalArgumentException("handshake secret must have at least 16 bytes");
        handshakeSecret = secret.clone(); handshakeClockSkew = millis(clockSkew); return this;
    }
    public RpcNodeBuilder handshakeSecret(byte[] secret) { return handshakeSecret(secret, Duration.ofSeconds(60)); }
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

