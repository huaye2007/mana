package cn.managame.rpc;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;

/**
 * Decides whether the node on the other side of a connection may join, from what it sent in its handshake, before
 * it can send or receive anything. It sees every connection, dialed by this node or dialing in; refused, the
 * connection closes, and a node dialing in leaves no peer behind.
 *
 * <p>The handshake is plain data, so checking a shared secret in its metadata keeps out mistakes, such as a test
 * server dialing a live cluster, not attackers.
 */
@FunctionalInterface
public interface RpcAuthenticator {
    /**
     * Runs on the connection's EventLoop and must not block; an exception thrown here refuses the node.
     *
     * @param connection the connection, for example to check its remote address
     * @param nodeId     the id the other node gave
     * @param metadata   what the other node put in its handshake
     */
    boolean authenticate(Connection connection, int nodeId, Metadata metadata);
}
