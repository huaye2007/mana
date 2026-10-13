package cn.managame.router.call;

import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.message.RpcResponse;

/** Routed messages for the service role. Requests and responses are borrowed during the callback. */
public interface RouterHandler {
    void onRoutedRequest(RoutedRequest request);
    /** A routed call completed: the target's response, or a positive error the Router returned for it. */
    void onResponse(int targetNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    /** A routed call failed locally: timeout, transport loss, closure or a malformed response. */
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}
