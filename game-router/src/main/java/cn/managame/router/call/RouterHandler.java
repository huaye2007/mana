package cn.managame.router.call;

import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.message.RpcResponse;

/** Borrowed routed messages delivered by the existing RpcNode's routing extension. */
public interface RouterHandler {
    /** Registration/restore completion or a stopped verification failure; zero means all desired keys restored. */
    default void onRegistration(int routerId, int errorCode) {}
    void onRoutedRequest(RoutedRequest request);
    void onResponse(int sourceNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}
