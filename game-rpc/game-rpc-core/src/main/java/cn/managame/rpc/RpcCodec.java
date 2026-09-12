package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

/**
 * Complete RPC envelope codec, including handshakes and heartbeats. Business serialization belongs
 * to the caller. Implementations must support concurrent use.
 */
public interface RpcCodec {
    /**
     * Returns an owned frame without consuming input references or changing their indices. A frame
     * may retain Route.inner; callers may release their reference, but must not mutate those bytes
     * while the encoded frame or its network output remains in use.
     */
    ByteBuf encode(RpcMessage message);

    /** Returns message data with borrowed binary views; does not change input indices. */
    RpcMessage decode(ByteBuf input);
}
