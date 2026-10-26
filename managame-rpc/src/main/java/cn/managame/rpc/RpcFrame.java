package cn.managame.rpc;

import cn.managame.runtime.GameContext;
import io.netty.buffer.ByteBuf;

/** What travels between nodes. A frame holding a body owns it until it is written or handled. */
sealed interface RpcFrame {
    /** First frame each way on a connection: the sender's node. The side that opened it sends first. */
    record Hello(int nodeId) implements RpcFrame {}

    /** A request, or a notice when {@code requestId} is zero, sent by a task running with {@code caller}. */
    record Request(int requestId, GameContext caller, ByteBuf body) implements RpcFrame {}

    record Response(int requestId, ByteBuf body) implements RpcFrame {}

    record Failure(int requestId, int errorCode) implements RpcFrame {}
}
