package cn.managame.rpc;

import cn.managame.network.connection.Connection;
import io.netty.util.Timeout;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One connection between this node and a peer, and the calls sent on it that wait for an answer. It carries
 * calls once the peer's Hello has arrived on it.
 */
final class RpcLink {
    final Connection connection;
    /** The peer this node connected to, or null for a connection a peer opened. */
    final RpcPeer expected;
    final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    /** Set on the connection's EventLoop when the Hello arrives. */
    volatile RpcPeer peer;

    RpcLink(Connection connection, RpcPeer expected) {
        this.connection = connection;
        this.expected = expected;
    }

    @Override public String toString() { return connection.toString(); }

    /** A call waiting for its answer. */
    static final class Pending {
        final RpcCallback<Object> callback;
        volatile Timeout timeout;

        Pending(RpcCallback<Object> callback) { this.callback = callback; }

        void cancelTimeout() {
            Timeout current = timeout;
            if (current != null) current.cancel();
        }
    }
}
