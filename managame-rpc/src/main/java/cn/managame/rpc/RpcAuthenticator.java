package cn.managame.rpc;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;

/**
 * Decides whether the node on the other side of a connection may join, from what it sent in its Hello, before it
 * joins. Asked on every connection, dialed or dialing in; false, or an exception, closes the connection. A node
 * refused while dialing in leaves no peer behind.
 *
 * <p>The Hello's metadata is plain data, so checking a shared secret in it only keeps out mistakes, not attackers
 * who can read the traffic; over TLS ({@link RpcNode.Builder#serverSsl}, {@link RpcNode.Builder#clientSsl}) it is
 * protected, and with client certificates the connection's TLS session names the peer.
 */
@FunctionalInterface
public interface RpcAuthenticator {
    /** Runs on the connection's EventLoop and must not block. */
    boolean authenticate(Connection connection, int nodeId, Metadata metadata);
}
