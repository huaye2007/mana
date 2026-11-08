package cn.managame.rpc;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCounted;
import java.util.Objects;

/**
 * The answer to one call, under the call's {@code requestId}: {@code code} zero with the metadata and body the
 * callee replied with, or a failure code, from the callee, with whatever metadata and body it gave it, or from the
 * calling node itself (see {@link RpcPeer#call}), with neither. The callee hands it to the peer that asked, with the
 * connection the request came on:
 *
 * <pre>{@code
 * rpcPeer.reply(connection, RpcResponse.of(request.requestId(), body));
 * }</pre>
 *
 * Reference-counted through its body, like a request. Only the first answer to a request counts; the caller drops
 * later ones.
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

    /** A failure with nothing more to say; {@code code} must be positive (IllegalArgumentException otherwise). */
    public static RpcResponse failure(int requestId, int code) {
        return failure(requestId, code, Metadata.empty(), Unpooled.EMPTY_BUFFER);
    }

    /**
     * A failure that says more than its code, such as the values the code's message shows, in the application's format,
     * which the caller reads from the response as it reads a success; {@code code} must be positive
     * (IllegalArgumentException otherwise).
     */
    public static RpcResponse failure(int requestId, int code, Metadata metadata, ByteBuf body) {
        if (code <= 0) throw new IllegalArgumentException("A failure code must be positive: " + code);
        return new RpcResponse(requestId, code, metadata, body);
    }

    public boolean isSuccess() { return code == FrameworkErrorCodes.SUCCESS; }

    @Override public int refCnt() { return body.refCnt(); }
    @Override public RpcResponse retain() { body.retain(); return this; }
    @Override public RpcResponse retain(int increment) { body.retain(increment); return this; }
    @Override public RpcResponse touch() { body.touch(); return this; }
    @Override public RpcResponse touch(Object hint) { body.touch(hint); return this; }
    @Override public boolean release() { return body.release(); }
    @Override public boolean release(int decrement) { return body.release(decrement); }
}
