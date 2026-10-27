package cn.managame.rpc;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import cn.managame.network.netty.NettyConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCounted;
import java.util.Objects;

/**
 * The answer to one call, under the call's {@code requestId}: {@code code} zero with the metadata and body the
 * callee replied with, or a failure code with no metadata and an empty body, from the callee or from the calling
 * node itself (see {@link RpcPeer#call}). The callee writes it to the connection the request came on:
 *
 * <pre>{@code
 * RpcResponse.of(request.requestId(), body).writeTo(connection);
 * }</pre>
 *
 * Reference-counted through its body, like a request. Only the first answer to a request counts; the caller drops
 * later ones. A response larger than the largest frame reaches the caller as RPC_PROTOCOL_ERROR instead.
 */
public record RpcResponse(int requestId, int code, Metadata metadata, ByteBuf body) implements ReferenceCounted {
    public RpcResponse {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(body, "body");
    }

    /** Success with no metadata. */
    public static RpcResponse of(int requestId, ByteBuf body) { return of(requestId, Metadata.empty(), body); }

    /** Success. */
    public static RpcResponse of(int requestId, Metadata metadata, ByteBuf body) {
        return new RpcResponse(requestId, FrameworkErrorCodes.SUCCESS, metadata, body);
    }

    /** A failure; {@code code} must be positive (IllegalArgumentException otherwise). */
    public static RpcResponse failure(int requestId, int code) {
        if (code <= 0) throw new IllegalArgumentException("A failure code must be positive: " + code);
        return new RpcResponse(requestId, code, Metadata.empty(), Unpooled.EMPTY_BUFFER);
    }

    public boolean isSuccess() { return code == FrameworkErrorCodes.SUCCESS; }

    /**
     * Writes this answer to {@code connection}, the one its request came on, from any thread, and releases it when
     * the connection does not take it. Unlike {@code connection.write}, it writes even when the connection is busy:
     * the work behind an answer is done, and dropping it would leave the caller unsure whether it was. False when
     * the connection has closed.
     */
    public boolean writeTo(Connection connection) {
        if (connection instanceof NettyConnection netty) {
            Channel channel = netty.channel();
            if (channel.isActive()) {
                channel.writeAndFlush(this, channel.voidPromise());
                return true;
            }
        } else if (connection.write(this) == WriteStatus.ACCEPTED) {
            return true;
        }
        release();
        return false;
    }

    @Override public int refCnt() { return body.refCnt(); }
    @Override public RpcResponse retain() { body.retain(); return this; }
    @Override public RpcResponse retain(int increment) { body.retain(increment); return this; }
    @Override public RpcResponse touch() { body.touch(); return this; }
    @Override public RpcResponse touch(Object hint) { body.touch(hint); return this; }
    @Override public boolean release() { return body.release(); }
    @Override public boolean release(int decrement) { return body.release(decrement); }
}
