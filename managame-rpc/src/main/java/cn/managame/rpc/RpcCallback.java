package cn.managame.rpc;

/**
 * Receives the answer to one call, exactly once, together with the request it answers, so that the application
 * can carry on with the request's command, route key and business, for example on the executor that key maps to.
 *
 * <p>It runs on one of the node's EventLoops when the answer arrives, the call times out or its connection closes;
 * on the thread that removed the peer or closed the node; or on the calling thread when the call fails before it
 * is sent. Either way it must not block: hand longer work to the application's own executor. An exception it throws
 * is logged.
 */
@FunctionalInterface
public interface RpcCallback {
    /**
     * @param request  the request as it was sent, with the id the call assigned, but with an empty body: the body
     *                 was released once sent
     * @param response the answer; it is released when this method returns, so decode it here or retain it
     */
    void onResponse(RpcRequest request, RpcResponse response);
}
