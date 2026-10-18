package cn.managame.boot.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.Objects;

/**
 * Thread-safe business codec. Arrays and decoded objects must not borrow transport buffers.
 * Implement the byte-array pair; override the ByteBuf pair to encode straight into a (pooled) buffer and
 * decode from the received frame without the intermediate copies.
 */
public interface GameRpcCodec {
    byte[] encode(Object message);

    <T> T decode(byte[] body, Class<T> type);

    /** Encodes into a new buffer owned by the caller. The default wraps {@link #encode(Object)}. */
    default ByteBuf encode(Object message, ByteBufAllocator allocator) {
        return Unpooled.wrappedBuffer(Objects.requireNonNull(encode(message), "GameRpcCodec.encode returned null"));
    }

    /**
     * Decodes a borrowed body (null for an empty one); the result must not keep a reference to it.
     * The default copies the readable bytes and calls {@link #decode(byte[], Class)}.
     */
    default <T> T decode(ByteBuf body, Class<T> type) {
        return decode(body == null ? new byte[0] : ByteBufUtil.getBytes(body), type);
    }
}
