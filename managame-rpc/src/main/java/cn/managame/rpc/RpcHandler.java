package cn.managame.rpc;

import cn.managame.network.connection.Connection;

/**
 * Receives what peers send to this node, on connections in either direction, and hears them come and go:
 *
 * <pre>{@code
 * RpcHandler handler = (rpcPeer, connection, msg) -> {
 *     switch (msg) {
 *         case RpcRequest request -> {             // answered under its id, now or later, from any thread
 *             Object query = protocol.decode(request.command(), request.body());
 *             executor.execute(() -> rpcPeer.reply(connection, RpcResponse.of(request.requestId(), protocol.encode(answer(query)))));
 *         }
 *         case RpcNotify notify -> handle(rpcPeer, protocol.decode(notify.command(), notify.body()));
 *         default -> {}
 *     }
 * };
 * }</pre>
 *
 * A handler that only receives is a lambda, as above; one that keeps something about its peers is a class, which
 * also implements {@link #onConnected} and {@link #onDisconnected}, where it clears what it kept for a peer that left.
 *
 * <p>Handshakes, heartbeats and answers to this node's own calls never come here: the node handles them, and hands
 * each answer to its call's {@link RpcCallback}.
 */
@FunctionalInterface
public interface RpcHandler {
    /**
     * An {@link RpcRequest}, to answer by {@link RpcPeer#reply} with {@code connection}, the one it came on, and an
     * {@link RpcResponse} with its {@code requestId}, or an {@link RpcNotify}, which expects no answer; {@code rpcPeer}
     * is the node that sent it, for instance to call it back. Runs on the connection's EventLoop, in the order the
     * messages arrived on it, and must not block: hand the work to wherever the application runs its logic. The
     * message is released when this returns, so decode it here, or retain it to keep it longer and release it when
     * done. An exception thrown here fails a request with RPC_HANDLER_ERROR, unless it was answered already; for a
     * notify it is logged.
     */
    void handleMessage(RpcPeer rpcPeer, Connection connection, Object msg);

    /**
     * Every connection of a running instance of the peer completed its handshake, whichever side dialed. An
     * application that keeps state about its peers keeps the peer's {@link RpcPeer#incarnation() incarnation} with
     * it: when that differs here, a new instance came and the state is gone; when it is the same, the instance only
     * came back after its connections dropped. A peer with several connections may send on those up before the last
     * completes its handshake, so a message of its may come before this.
     *
     * <p>This and {@link #onDisconnected} never overlap for one peer and come in order. They run on the EventLoop of
     * the connection that changed and must not block: hand the work to wherever the application runs its logic.
     * They may call, notify and answer any peer. An exception thrown here is logged. Nothing by default.
     */
    default void onConnected(RpcPeer rpcPeer) {}

    /**
     * The last connection of the peer closed, also when the peer was removed, this node closed or a new instance of
     * the peer took over: where an application clears what it keeps for the peer, such as the players a game server
     * had in this world. Only after {@link #onConnected}: a peer that loses some of its connections stays connected.
     * Nothing by default.
     */
    default void onDisconnected(RpcPeer rpcPeer) {}
}
