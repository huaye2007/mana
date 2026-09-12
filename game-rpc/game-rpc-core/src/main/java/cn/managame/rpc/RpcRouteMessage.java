package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

/**
 * Opaque application envelope. RPC never interprets addresses or inner contents. Borrows inner
 * without retaining it.
 */
public record RpcRouteMessage(int sourceNodeId, int targetNodeId, ByteBuf inner)
        implements RpcMessage {
    public RpcRouteMessage {
        Objects.requireNonNull(inner, "inner");
        if (!inner.isReadable()) throw new IllegalArgumentException("Empty routed message");
    }
}
