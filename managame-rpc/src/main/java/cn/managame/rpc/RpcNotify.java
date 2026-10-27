package cn.managame.rpc;

import cn.managame.Metadata;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import java.util.Objects;

/**
 * A message to another node that expects no answer, as the sender builds it and as the receiver's
 * {@link RpcHandler} gets it. Its fields mean what they mean in {@link RpcRequest}: the application's, carried as
 * is, with {@code routeKey} also picking the connection, so notifies with one key arrive in the order they were
 * sent, also among requests with that key. Reference-counted through its body, like a request.
 */
public record RpcNotify(int command, long routeKey, byte businessType, long businessId, Metadata metadata, ByteBuf body)
        implements ReferenceCounted {
    public RpcNotify {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(body, "body");
    }

    /** A notify with route key 0, no business and no metadata. */
    public static RpcNotify of(int command, ByteBuf body) { return of(command, 0, body); }

    /** A notify with no business and no metadata. */
    public static RpcNotify of(int command, long routeKey, ByteBuf body) {
        return new RpcNotify(command, routeKey, (byte) 0, 0, Metadata.empty(), body);
    }

    /** The same notify over a retained duplicate of the body, to send it once more. */
    RpcNotify retainedDuplicate() {
        return new RpcNotify(command, routeKey, businessType, businessId, metadata, body.retainedDuplicate());
    }

    @Override public int refCnt() { return body.refCnt(); }
    @Override public RpcNotify retain() { body.retain(); return this; }
    @Override public RpcNotify retain(int increment) { body.retain(increment); return this; }
    @Override public RpcNotify touch() { body.touch(); return this; }
    @Override public RpcNotify touch(Object hint) { body.touch(hint); return this; }
    @Override public boolean release() { return body.release(); }
    @Override public boolean release(int decrement) { return body.release(decrement); }
}
