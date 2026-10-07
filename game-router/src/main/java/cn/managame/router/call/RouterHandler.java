package cn.managame.router.call;

import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.message.RpcResponse;

/** Borrowed routed messages delivered by the existing RpcNode's routing extension. */
public interface RouterHandler {
    /** Initial registration and each reconnect/restore attempt; zero means all desired keys restored. */
    default void onRegistration(int routerId, int errorCode) {}
    void onRoutedRequest(RoutedRequest request);
    void onResponse(int sourceNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}
