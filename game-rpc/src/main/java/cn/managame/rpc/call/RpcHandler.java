package cn.managame.rpc.call;

import cn.managame.rpc.message.RpcRequest;
import cn.managame.rpc.message.RpcResponse;

/**
 * Must be thread-safe and nonblocking. Request/response normally run on Netty EventLoops;
 * failures may run on caller, timer, topology or shutdown threads.
 * The application owns body decoding, error interpretation and business dispatch.
 */
public interface RpcHandler {
    /** Body is borrowed. Retain/copy before saving it or transferring it to reply(). */
    void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request);
    /** Pending call is removed and timeout cancelled before entry. Body is borrowed. */
    void onResponse(int sourceNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    /** A local outbound call failure; remote error responses go to onResponse instead. */
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}

