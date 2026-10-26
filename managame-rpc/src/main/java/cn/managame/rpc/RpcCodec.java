package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

/**
 * The application's encoding of RPC messages: requests, notices and results. It must be self-describing, since
 * the receiver tells messages apart by what it decodes. Thread-safe: requests are encoded on the calling thread,
 * results on the answering thread, and decoding runs on the connection's EventLoop.
 */
public interface RpcCodec {
    void encode(Object message, ByteBuf out) throws Exception;

    /** The body is released afterwards, so the message must not keep it. */
    Object decode(ByteBuf body) throws Exception;
}
