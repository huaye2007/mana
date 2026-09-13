package cn.managame.rpc.core;


import cn.managame.network.Connection;

@FunctionalInterface
public interface RpcHandler {
    /**
     * Receives a decoded RpcRequest or opaque RpcRouteMessage. RPC does not interpret route
     * addresses or inner bytes. Body/inner are borrowed for this callback; asynchronous consumers
     * must copy or retain them and eventually release their own references.
     */
    void handleUserMsg(Connection connection, Object msg);
}
