package cn.managame.rpc;

/**
 * Runs directly on the completing thread: the caller, network, or timer thread. RPC does not
 * dispatch callbacks to an executor. The caller must dispatch business work and return promptly. A
 * successful response body is borrowed for the duration of onResult; copy or retain it before
 * asynchronous use, and release independently retained buffers. Remote error responses are also
 * available through result.value(); their string arguments are independent of the network buffer.
 */
@FunctionalInterface
public interface RpcCallback {
    void onResult(RpcResult result);
}
