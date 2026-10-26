package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

/**
 * The application's encoding of RPC messages: requests, notices and results. It must be self-describing, since
 * the receiver picks the handler by the decoded message's class. Thread-safe; encoding runs on the calling
 * thread, decoding on the connection's EventLoop.
 */
public interface RpcCodec {
    void encode(Object message, ByteBuf out) throws Exception;

    /** The body is released afterwards, so the message must not keep it. */
    Object decode(ByteBuf body) throws Exception;
}
