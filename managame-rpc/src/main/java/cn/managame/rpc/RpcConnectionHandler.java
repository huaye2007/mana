package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_HANDLER_ERROR;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.rpc.RpcCodec.Handshake;
import cn.managame.rpc.RpcCodec.Heartbeat;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.lang.System.Logger.Level;

/**
 * What every connection of a node does with its frames, shared by all of them: the handshake that joins it to a
 * peer, then answers to the peer's calls, the peer's requests and notifies to the application's handler, and the
 * dialing side's heartbeats, which the accepting side answers. Received frames are released by network after each
 * call returns.
 */
final class RpcConnectionHandler implements ConnectionHandler {
    /** The peer a connection was dialed to; unset on connections peers dial in. */
    static final AttributeKey<RpcPeer> DIALED = AttributeKey.valueOf("cn.managame.rpc.dialed");
    /** The slot of a connection this node dialed, among the connections to its peer. */
    static final AttributeKey<Integer> SLOT = AttributeKey.valueOf("cn.managame.rpc.slot");
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

    /** The dialing side speaks first, naming its slot and its connections. */
    @Override public void onActive(Connection connection) {
        if (connection.get(DIALED) instanceof RpcPeer dialed) sendHandshake(connection, connection.get(SLOT), dialed.slotCount());
    }

    @Override public void onRead(Connection connection, Object frame) {
        RpcPeer peer = RpcPeer.of(connection);
        if (frame instanceof Handshake theirs) {
            handshake(connection, peer, theirs);
        } else if (peer == null) {
            refuse(connection, "a frame came before the handshake");
        } else {
            switch (frame) {
                case RpcResponse response -> peer.answer(response);
                case RpcRequest request -> dispatch(peer, connection, request);
                case RpcNotify notify -> dispatch(peer, connection, notify);
                case Heartbeat heartbeat -> {
                    if (connection.get(DIALED) == null) RpcCodec.writeEvenIfBusy(connection, Heartbeat.INSTANCE);
                }
                default -> {}
            }
        }
    }

    /**
     * The other side named itself. Both must speak the same protocol version, and the authenticator must admit it.
     * The dialed node must be the one this node meant to reach. A node dialing in must not be this node or one this
     * node dials itself, since two nodes must not dial each other, and must name a slot among its connections; it is
     * answered once accepted and before it joins, so this node's handshake precedes anything sent once it has
     * joined.
     */
    private void handshake(Connection connection, RpcPeer joined, Handshake theirs) {
        int id = theirs.nodeId();
        RpcPeer dialed = connection.get(DIALED);
        String refusal = joined != null ? "a second handshake"
                : theirs.version() != RpcCodec.VERSION ? "node " + id + " speaks protocol version " + theirs.version() + ", this node " + RpcCodec.VERSION
                : dialed != null && id != dialed.nodeId() ? "node " + id + " answered instead of node " + dialed.nodeId()
                : dialed == null && id == node.nodeId() ? "the other side claims this node's id " + id
                : dialed == null && theirs.slot() >= theirs.slots() ? "node " + id + " names slot " + theirs.slot() + " of " + theirs.slots()
                : node.isClosed() ? "this node is closed"
                : !authenticate(connection, theirs) ? "node " + id + " was not admitted"
                : null;
        if (refusal == null) {
            RpcPeer peer = dialed != null ? dialed : node.inboundPeer(id);
            refusal = peer.isOutbound() && dialed == null
                    ? "node " + id + " dials in, but this node dials it; only one of two nodes may dial the other"
                    : dialed != null ? peer.addConnection(connection, theirs, connection.get(SLOT), () -> {})
                    : peer.addConnection(connection, theirs, theirs.slot(), () -> sendHandshake(connection, theirs.slot(), theirs.slots()));
        }
        if (refusal != null) refuse(connection, refusal);
    }

    private boolean authenticate(Connection connection, Handshake theirs) {
        if (authenticator == null) return true;
        try {
            return authenticator.authenticate(connection, theirs.nodeId(), theirs.metadata());
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "The RPC authenticator failed on node " + theirs.nodeId(), e);
            return false;
        }
    }

    private void sendHandshake(Connection connection, int slot, int slots) {
        RpcCodec.write(connection, new Handshake(RpcCodec.VERSION, node.nodeId(), node.incarnation(), slot, slots, node.metadata()));
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
        if (peer != null) peer.removeConnection(connection);
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
