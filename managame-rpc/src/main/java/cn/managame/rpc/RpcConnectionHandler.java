package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.rpc.RpcFrames.Goodbye;
import cn.managame.rpc.RpcFrames.GoodbyeAck;
import cn.managame.rpc.RpcFrames.Hello;
import cn.managame.rpc.RpcFrames.Unsent;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.lang.System.Logger.Level;

/**
 * What every connection of a node does with its frames, shared by all of them: the handshake that joins it to a
 * peer, then answers to the peer's calls, the peer's requests and notifies to the application's handler, and Goodbye
 * to the peer, which is acknowledged on the connection it came on. Pings only had to arrive. Received frames are
 * released by network after each call returns.
 */
final class RpcConnectionHandler implements ConnectionHandler {
    /** The peer a connection was dialed to; unset on connections peers dial in. */
    static final AttributeKey<RpcPeer> DIALED = AttributeKey.valueOf("cn.managame.rpc.dialed");
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    private final RpcNode node;
    private final RpcHandler handler;
    private final RpcAuthenticator authenticator;

    /** {@code authenticator} may be null to admit every node. */
    RpcConnectionHandler(RpcNode node, RpcHandler handler, RpcAuthenticator authenticator) {
        this.node = node;
        this.handler = handler;
        this.authenticator = authenticator;
    }

    /** The dialing side speaks first. */
    @Override public void onActive(Connection connection) {
        if (connection.get(DIALED) != null) hello(connection);
    }

    @Override public void onRead(Connection connection, Object frame) {
        RpcPeer peer = RpcPeer.of(connection);
        if (frame instanceof Hello hello) {
            handshake(connection, peer, hello);
        } else if (peer == null) {
            refuse(connection, "a frame came before the Hello");
        } else {
            switch (frame) {
                case RpcResponse response -> peer.answer(response);
                case RpcRequest request -> dispatch(peer, connection, request);
                case RpcNotify notify -> dispatch(peer, connection, notify);
                case Goodbye goodbye -> {
                    peer.closing(); // calls made from now on fail at once; the codec refuses those that raced it
                    RpcFrames.sendAnyway(connection, GoodbyeAck.INSTANCE);
                }
                case GoodbyeAck ack -> peer.acknowledged(connection);
                default -> {} // a Ping: receiving it was the point
            }
        }
    }

    /** A request the codec did not send, as the peer had said Goodbye: its call fails at once. */
    @Override public void onEvent(Connection connection, Object event) {
        if (event instanceof Unsent unsent && RpcPeer.of(connection) instanceof RpcPeer peer) peer.unsent(unsent.requestId());
    }

    /**
     * The other side named itself. Both must share a protocol version, and the authenticator must admit it. The
     * dialed node must be the one this node meant to reach. A node dialing in must not be this node or one this node
     * dials itself, since two nodes must not dial each other; it is answered once accepted and before it joins, so
     * this node's Hello precedes anything sent once it has joined.
     */
    private void handshake(Connection connection, RpcPeer joined, Hello hello) {
        int id = hello.nodeId();
        RpcPeer dialed = connection.get(DIALED);
        String refusal = joined != null ? "a second Hello"
                : Math.min(RpcFrames.MAX_VERSION, hello.maxVersion()) < Math.max(RpcFrames.MIN_VERSION, hello.minVersion())
                ? "node " + id + " speaks protocol versions " + hello.minVersion() + " to " + hello.maxVersion()
                + ", this node " + RpcFrames.MIN_VERSION + " to " + RpcFrames.MAX_VERSION
                : dialed != null && id != dialed.nodeId() ? "node " + id + " answered instead of node " + dialed.nodeId()
                : dialed == null && id == node.nodeId() ? "the other side claims this node's id " + id
                : dialed == null && node.isClosing() ? "this node is closing"
                : !authenticate(connection, hello) ? "node " + id + " was not admitted"
                : null;
        if (refusal == null) {
            RpcPeer peer = dialed != null ? dialed : node.dialingIn(id);
            refusal = peer.isOutbound() && dialed == null
                    ? "node " + id + " dials in, but this node dials it; only one of two nodes may dial the other"
                    : peer.attach(connection, hello, dialed != null ? () -> {} : () -> hello(connection));
        }
        if (refusal != null) refuse(connection, refusal);
        else if (node.isClosing()) RpcFrames.sendAnyway(connection, Goodbye.INSTANCE); // joined while this node drains
    }

    private boolean authenticate(Connection connection, Hello hello) {
        if (authenticator == null) return true;
        try {
            return authenticator.authenticate(connection, hello.nodeId(), hello.metadata());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "The RPC authenticator failed on node " + hello.nodeId(), e);
            return false;
        }
    }

    private void hello(Connection connection) {
        RpcFrames.send(connection, new Hello(RpcFrames.MIN_VERSION, RpcFrames.MAX_VERSION, node.nodeId(), node.incarnation(), node.metadata()));
    }

    private void dispatch(RpcPeer peer, Connection connection, Object msg) {
        try {
            handler.handleMessage(peer, connection, msg);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "The RPC handler failed on " + msg + " from node " + peer.nodeId(), e);
            if (msg instanceof RpcRequest request) RpcResponse.failure(request.requestId(), RPC_HANDLER_ERROR).writeTo(connection);
        }
    }

    /** The connection leaves its peer. */
    @Override public void onInActive(Connection connection) {
        RpcPeer peer = RpcPeer.of(connection);
        if (peer != null) peer.detach(connection);
    }

    @Override public void onException(Connection connection, Throwable cause) {
        if (cause instanceof IOException) {
            if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, "RPC connection failed; " + connection + "; " + cause);
        } else {
            LOG.log(Level.WARNING, "Closing RPC connection " + connection, cause);
        }
        connection.close();
    }

    private static void refuse(Connection connection, String reason) {
        LOG.log(Level.WARNING, "Closing RPC connection " + connection + ": " + reason);
        connection.close();
    }
}
