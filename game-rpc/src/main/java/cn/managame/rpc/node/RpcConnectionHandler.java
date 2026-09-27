package cn.managame.rpc.node;

import cn.managame.network.connection.*;
import io.netty.handler.timeout.*;

/** Package-private Network adapter; the application never receives mutable session state. */
final class RpcConnectionHandler implements ConnectionHandler {
    private final RpcNode node;
    RpcConnectionHandler(RpcNode node) { this.node = node; }
    public void onConnected(Connection connection) { node.connected(connection); }
    public void onMessage(Connection connection, Object message) { node.receive(connection, message); }
    public void onDisconnected(Connection connection) { node.disconnected(connection); }
    public void onException(Connection connection, Throwable cause) {
        RpcNode.log("RPC connection failed", cause);
        connection.close();
    }
    public void onEvent(Connection connection, Object event) {
        if (event instanceof IdleStateEvent idle) node.idle(connection, idle.state());
    }
}

