package cn.managame.rpc;

/**
 * Sees every call this node made end, answered or failed, for metrics such as latency, timeouts and failures per
 * peer and command, without tying the node to a monitoring system. Together with {@link RpcPeer#pendingCount()},
 * {@link RpcNode#unansweredCount()} and the {@link RpcListener}, it tells how the node and its peers are doing.
 */
@FunctionalInterface
public interface RpcObserver {
    /**
     * Runs right before the call's callback, on the same thread, and must not block; an exception is logged. The
     * request is the one the callback gets, without its body; the response must not be kept.
     *
     * @param elapsedNanos from the call to its end
     */
    void onCallSettled(RpcPeer peer, RpcRequest request, RpcResponse response, long elapsedNanos);
}
