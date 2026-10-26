package cn.managame.rpc;

import cn.managame.runtime.Context;

/** What a call does when it fails; runs as a task on the calling context's route. */
@FunctionalInterface
public interface RpcFailure {
    /**
     * {@code errorCode} is RPC_UNAVAILABLE, RPC_TIMEOUT or RPC_NODE_CLOSED for a failure on this side, otherwise
     * the code the remote side failed the request with.
     */
    void onFailure(Context context, int errorCode) throws Exception;
}
