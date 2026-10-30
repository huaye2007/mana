package cn.managame.rpc;

import cn.managame.Metadata;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCounted;
import java.util.Objects;

/**
 * A call to another node, as the caller builds it and as the callee's {@link RpcHandler} receives it. Every field
 * but the id and the deadline is the application's and travels as is: {@code command} says what is asked,
 * {@code businessType} and {@code businessId} whom it is about, {@code metadata} anything else, such as a trace id.
 * {@code routeKey} also picks the connection, so requests and notifies with one key reach the peer in the order
 * they were sent.
 *
 * <p>The {@code requestId} and the {@code deadline} are the calling node's: build a request with {@link #of} and
 * the call assigns both, ignoring whatever the request carried, so a received request can be passed on as is. The
 * callee answers with an {@link RpcResponse} under the same id. The deadline is a {@link System#nanoTime()} value
 * of the process holding the request, 0 for none: the caller stops waiting then, so the callee may skip a request
 * that {@link #isExpired()}. It travels as the time left, so the two clocks need not agree.
 *
 * <p>Like Netty's own messages, it is reference-counted through its body; {@link RpcPeer} and {@link RpcHandler}
 * say who releases it.
 */
public record RpcRequest(int requestId, long deadline, int command, long routeKey, byte businessType, long businessId,
                         Metadata metadata, ByteBuf body) implements ReferenceCounted {
    public RpcRequest {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(body, "body");
    }

    /** A request with route key 0, no business and no metadata. */
    public static RpcRequest of(int command, ByteBuf body) { return of(command, 0, body); }

    /** A request with no business and no metadata. */
    public static RpcRequest of(int command, long routeKey, ByteBuf body) {
        return of(command, routeKey, (byte) 0, 0, Metadata.empty(), body);
    }

    public static RpcRequest of(int command, long routeKey, byte businessType, long businessId, Metadata metadata,
                                ByteBuf body) {
        return new RpcRequest(0, 0, command, routeKey, businessType, businessId, metadata, body);
    }

    /** Whether the caller has stopped waiting for the answer. */
    public boolean isExpired() { return deadline != 0 && System.nanoTime() - deadline >= 0; }

    /** What a callback receives: this request as it was sent under {@code id}, without its body, which was released. */
    RpcRequest sentAs(int id, long deadline) {
        return new RpcRequest(id, deadline, command, routeKey, businessType, businessId, metadata, Unpooled.EMPTY_BUFFER);
    }

    @Override public int refCnt() { return body.refCnt(); }
    @Override public RpcRequest retain() { body.retain(); return this; }
    @Override public RpcRequest retain(int increment) { body.retain(increment); return this; }
    @Override public RpcRequest touch() { body.touch(); return this; }
    @Override public RpcRequest touch(Object hint) { body.touch(hint); return this; }
    @Override public boolean release() { return body.release(); }
    @Override public boolean release(int decrement) { return body.release(decrement); }
}
