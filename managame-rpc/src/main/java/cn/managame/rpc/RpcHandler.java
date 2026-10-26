package cn.managame.rpc;

/**
 * The application's handling of the requests and notices peers send. It runs on the connection's EventLoop and
 * must not block: it hands the request to wherever the application runs its logic, which answers it later through
 * the request. An exception thrown here fails the request with RPC_HANDLER_ERROR.
 */
@FunctionalInterface
public interface RpcHandler {
    void onRequest(RpcRequest request) throws Exception;
}
