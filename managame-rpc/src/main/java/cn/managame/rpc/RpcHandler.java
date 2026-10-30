package cn.managame.rpc;

import cn.managame.network.connection.Connection;

/**
 * Receives what peers send to this node, on connections in either direction:
 *
 * <pre>{@code
 * RpcHandler handler = (rpcPeer, connection, msg) -> {
 *     switch (msg) {
 *         case RpcRequest request -> {             // answered under its id, now or later, from any thread
 *             Object query = protocol.decode(request.command(), request.body());
 *             executor.execute(() -> RpcResponse.of(request.requestId(), protocol.encode(answer(query))).writeTo(connection));
 *         }
 *         case RpcNotify notify -> handle(rpcPeer, protocol.decode(notify.command(), notify.body()));
 *         default -> {}
 *     }
 * };
 * }</pre>
 *
 * Handshakes, heartbeats and answers to this node's own calls never come here: the node handles them, and hands
 * each answer to its call's {@link RpcCallback}.
 */
@FunctionalInterface
public interface RpcHandler {
    /**
     * An {@link RpcRequest}, to answer by writing an {@link RpcResponse} with its {@code requestId} to
     * {@code connection}, the one it came on, or an {@link RpcNotify}, which expects no answer; {@code rpcPeer} is
     * the node that sent it, for instance to call it back. Runs on the connection's EventLoop, in the order the
     * messages arrived on it, and must not block: hand the work to wherever the application runs its logic. The
     * message is released when this returns, so decode it here, or retain it to keep it longer and release it when
     * done. An exception thrown here fails a request with RPC_HANDLER_ERROR, unless it was answered already; for a
     * notify it is logged.
     */
    void handleMessage(RpcPeer rpcPeer, Connection connection, Object msg);
}
