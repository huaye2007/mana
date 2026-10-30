package cn.managame.rpc;

import cn.managame.Metadata;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import java.util.Objects;

/**
 * A one-way message to another node: no answer, no id. The fields are the application's and travel as is, like a
 * {@link RpcRequest}'s; {@code routeKey} also picks the connection, so messages with one key keep their order.
 * Reference-counted through its body.
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

    public static RpcNotify of(int command, long routeKey, byte businessType, long businessId, Metadata metadata,
                               ByteBuf body) {
        return new RpcNotify(command, routeKey, businessType, businessId, metadata, body);
    }

    /** The same notify sharing this body, retained once more; how a broadcast sends one body to many peers. */
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
