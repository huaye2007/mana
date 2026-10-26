package cn.managame.rpc;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * What a call does with its answer: exactly one of the methods runs, once. They run on a network thread, the
 * connection's EventLoop or the timeout thread, or on the calling thread when the call fails at once, so they must
 * not block; hand longer work to the application's own executor. An exception from them is logged.
 */
public interface RpcCallback<R> {
    void onResult(R result);

    /**
     * {@code errorCode} is RPC_UNAVAILABLE, RPC_TIMEOUT or RPC_NODE_CLOSED for a failure on this side, otherwise the
     * code the peer failed the request with.
     */
    void onFailure(int errorCode);

    /** A callback from two lambdas. */
    static <R> RpcCallback<R> of(Consumer<? super R> onResult, IntConsumer onFailure) {
        Objects.requireNonNull(onResult, "onResult");
        Objects.requireNonNull(onFailure, "onFailure");
        return new RpcCallback<>() {
            @Override public void onResult(R result) { onResult.accept(result); }
            @Override public void onFailure(int errorCode) { onFailure.accept(errorCode); }
        };
    }
}
