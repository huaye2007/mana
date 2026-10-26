package cn.managame.rpc;

import cn.managame.Metadata;
import io.netty.buffer.ByteBuf;

/** What travels between nodes. A frame holding a body owns it until it is written or handled. */
sealed interface RpcFrame {
    /** First frame each way on a connection: the sender's node. The side that opened it sends first. */
    record Hello(int nodeId) implements RpcFrame {}

    /** A request, or a notice when {@code requestId} is zero, with what the sender attached. */
    record Request(int requestId, Metadata metadata, ByteBuf body) implements RpcFrame {}

    record Response(int requestId, ByteBuf body) implements RpcFrame {}

    record Failure(int requestId, int errorCode) implements RpcFrame {}

    /** Sent by a side that has written nothing for a while, so that the other side knows it is alive. */
    record Ping() implements RpcFrame {
        static final Ping INSTANCE = new Ping();
    }
}
