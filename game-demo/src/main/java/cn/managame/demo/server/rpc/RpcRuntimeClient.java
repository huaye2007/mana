package cn.managame.demo.server.rpc;

import cn.managame.rpc.core.RpcCallback;
import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.runtime.execution.CallbackDefinition;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.execution.RuntimeCallback;
import cn.managame.runtime.route.Route;

import cn.managame.demo.protocol.GameCallException;
import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.serialization.MessageSerializer;
import cn.managame.demo.server.support.GameFailures;
import cn.managame.rpc.core.*;
import cn.managame.runtime.execution.*;
import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes borrowed RPC responses before delivering owned values/errors on a runtime route. */
public final class RpcRuntimeClient implements AutoCloseable {
    @FunctionalInterface
    interface TransportCall {
        void call(int peer, int command, ByteBuf body, RpcOptions options, RpcCallback callback);
    }

    private final GameRuntime runtime;
    private final TransportCall transport;
    private final Runnable stopCalls;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RpcRuntimeClient(GameRuntime runtime, RpcNode rpc) { this(runtime, rpc::call, rpc::stopCalls); }
    RpcRuntimeClient(GameRuntime runtime, TransportCall transport, Runnable stopCalls) {
        this.runtime = Objects.requireNonNull(runtime);
        this.transport = Objects.requireNonNull(transport);
        this.stopCalls = Objects.requireNonNull(stopCalls);
    }

    /** RPC owns remote timeouts. Admitted callbacks complete on-route; rejected/failed execution fails the wait directly. */
    public <Q, S> CompletableFuture<S> call(int peer, CommandBinding<Q, S> binding,
            Q request, Route callbackRoute, RpcOptions options) {
        if (closed.get()) return CompletableFuture.failedFuture(new GameCallException(RpcError.UNAVAILABLE.code(), List.of()));
        Delivery<S> delivery;
        try { delivery = new Delivery<>(callbackRoute); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(GameFailures.from(failure)); }
        try {
            ByteBuf body = MessageSerializer.serializeBuffer(binding.requestType().cast(request));
            try {
                transport.call(peer, binding.id(), body, options, result -> {
                    if (delivery.signalled.get()) return;
                    try {
                        if (result.isSuccess()) {
                            S response = MessageSerializer.deserialize(result.value().body(), binding.responseType());
                            delivery.success(response);
                        } else {
                            delivery.failure(new GameCallException(result.errorCode(), result.errorArgs(), result.cause()));
                        }
                    } catch (RuntimeException failure) { delivery.failure(failure); }
                });
            } finally { body.release(); }
        } catch (RuntimeException failure) { delivery.failure(failure); }
        return delivery.future.copy();
    }

    private final class Delivery<S> {
        private final CompletableFuture<S> future = new CompletableFuture<>();
        private final AtomicBoolean signalled = new AtomicBoolean();
        private final RuntimeCallback<S> callback;

        Delivery(Route route) {
            callback = runtime.callback(new CallbackDefinition<S>(Objects.requireNonNull(route), future::complete,
                    future::completeExceptionally));
            // Backend failure after admission may prevent either business callback from running.
            callback.completion().completionStage().whenComplete((ignored, failure) -> {
                if (failure != null) future.completeExceptionally(GameFailures.from(failure));
            });
        }

        void success(S response) {
            if (!signalled.compareAndSet(false, true)) return;
            deliver(() -> callback.onSuccess(response), null);
        }

        void failure(Throwable error) {
            if (!signalled.compareAndSet(false, true)) return;
            GameCallException failure = GameFailures.from(error);
            deliver(() -> callback.onFail(failure), failure);
        }

        private void deliver(Runnable signal, Throwable originalFailure) {
            try { signal.run(); }
            catch (RuntimeException rejected) {
                GameCallException failure = GameFailures.from(rejected);
                if (originalFailure != null && failure != originalFailure) failure.addSuppressed(originalFailure);
                callback.abort(failure);
                future.completeExceptionally(failure);
            }
        }
    }

    /** Stops this node's outbound calls while keeping its transport available for replies. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) stopCalls.run();
    }
}
