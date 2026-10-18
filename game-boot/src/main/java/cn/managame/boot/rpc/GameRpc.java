package cn.managame.boot.rpc;

import cn.managame.core.Metadata;
import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.*;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.*;
import cn.managame.runtime.protocol.*;
import cn.managame.runtime.route.CapturedRouteCallback;
import cn.managame.runtime.route.RouteCallback;
import io.netty.buffer.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.managame.rpc.error.RpcErrorCodes.*;

/**
 * Typed RPC adapter; business methods and captured callbacks execute through Runtime Routes. Constructing
 * one is the whole join between RPC and Runtime: nothing else has to be wired, and no container has to run.
 * An application assembles it directly, and the Spring integration does exactly the same thing with beans.
 */
public final class GameRpc implements AutoCloseable {
    private final GameRuntime runtime;
    private final GameRpcCodec codec;
    private final RpcNode node;
    private final List<GameRpcConfigurer> configurers;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Applies configure, then decorate, in list order, builds the Node, then attach in order; a failed
     * attach detaches what already attached and closes the built Node.
     *
     * <p>All four hooks run here rather than some of them at the call site, so an integration only has to
     * decide the order of the list. {@code builder} carries whatever the caller already configured —
     * node id, bind address, call timeout — and its handler is replaced with this adapter's.
     *
     * @param configurers applied in list order; a Spring integration passes them in bean order
     */
    public GameRpc(GameRuntime runtime, GameRpcCodec codec, RpcNodeBuilder builder,
                   List<GameRpcConfigurer> configurers) {
        this.runtime = Objects.requireNonNull(runtime);
        this.codec = Objects.requireNonNull(codec);
        this.configurers = List.copyOf(configurers);
        Objects.requireNonNull(builder);
        for (GameRpcConfigurer configurer : this.configurers) configurer.configure(builder);
        RpcHandler handler = new Handler();
        for (GameRpcConfigurer configurer : this.configurers)
            handler = Objects.requireNonNull(configurer.decorate(handler), "GameRpcConfigurer.decorate returned null");
        node = builder.handler(handler).build();
        int attached = 0;
        try {
            for (GameRpcConfigurer configurer : this.configurers) { configurer.attach(node); attached++; }
        } catch (RuntimeException | Error failure) {
            closed.set(true);
            detach(attached, failure);
            try { node.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void detach(int count, Throwable failure) {
        for (int i = count - 1; i >= 0; i--) {
            try { configurers.get(i).detach(node); }
            catch (RuntimeException | Error error) {
                if (failure != null) failure.addSuppressed(error);
                else System.getLogger(GameRpc.class.getName()).log(System.Logger.Level.WARNING, "GameRpcConfigurer.detach failed", error);
            }
        }
    }

    /** The managed Node, for topology management and start; its lifecycle belongs to this adapter. */
    public RpcNode node() { return node; }

    /** Uses the registered request/response binding. Must be called on this Runtime's Route. */
    public <T> void call(int targetNodeId, long routeKey, int businessIdType, long businessId,
                         Metadata metadata, Object request, RouteCallback<T> callback) {
        ProtocolDescriptor protocol = requestProtocol(request);
        Class<?> responseType = protocol.responseType();
        if (responseType == null) throw new IllegalArgumentException("No response binding: " + request.getClass());
        Objects.requireNonNull(callback);
        ByteBuf body = encode(request);
        CapturedRouteCallback<T> captured;
        try { captured = runtime.callback(callback); }
        catch (RuntimeException | Error failure) { body.release(); throw failure; }
        Completion<T> completion = new Completion<>(responseType, captured);
        try {
            node.call(targetNodeId, new RpcRequest(protocol.command(), routeKey, businessIdType,
                    businessId, metadata, body), completion);
        } catch (RuntimeException | Error failure) {
            if (body.refCnt() > 0) body.release();
            // The caller learns about the failure from the exception alone; only release the Route capture.
            captured.cancel();
            throw failure;
        }
    }

    public RpcSendStatus notify(int targetNodeId, long routeKey, int businessIdType, long businessId,
                                Metadata metadata, Object request) {
        ProtocolDescriptor protocol = requestProtocol(request);
        ByteBuf body = encode(request);
        try {
            return node.notify(targetNodeId, new RpcRequest(protocol.command(), routeKey, businessIdType,
                    businessId, metadata, body));
        } catch (RuntimeException | Error failure) {
            if (body.refCnt() > 0) body.release();
            throw failure;
        }
    }

    public RpcSendStatus reply(Object response) {
        return reply(Contexts.current(RpcHandlerContext.class), response);
    }

    /** Explicit context also supports delayed replies; transport buffers are never retained. */
    public RpcSendStatus reply(RpcHandlerContext context, Object response) {
        requireCall(context);
        ProtocolDescriptor request = runtime.protocols().get(ProtocolDescriptor.Type.REQUEST, context.command());
        Class<?> expected = request == null ? null : request.responseType();
        if (response == null || expected != response.getClass())
            throw new IllegalArgumentException("Response does not match the request's registered response type");
        ByteBuf body = encode(response);
        try {
            return node.reply(context.sourceNodeId(), context.sourceSlotId(),
                    new RpcResponse(context.requestId(), 0, Metadata.empty(), body));
        } catch (RuntimeException | Error failure) {
            if (body.refCnt() > 0) body.release();
            throw failure;
        }
    }

    public RpcSendStatus replyError(RpcHandlerContext context, int errorCode) {
        requireCall(context);
        if (errorCode <= 0) throw new IllegalArgumentException("errorCode must be positive");
        return node.reply(context.sourceNodeId(), context.sourceSlotId(),
                new RpcResponse(context.requestId(), errorCode, Metadata.empty(), null));
    }

    private void requireCall(RpcHandlerContext context) {
        Objects.requireNonNull(context);
        if (context.requestId() == 0) throw new IllegalArgumentException("Notify has no response");
    }

    private ProtocolDescriptor requestProtocol(Object request) {
        Objects.requireNonNull(request);
        ProtocolDescriptor protocol = runtime.protocols().get(request.getClass());
        if (protocol == null || protocol.type() != ProtocolDescriptor.Type.REQUEST)
            throw new IllegalArgumentException("RPC input must be a registered REQUEST: " + request.getClass());
        return protocol;
    }

    private ByteBuf encode(Object message) {
        return Objects.requireNonNull(codec.encode(message, ByteBufAllocator.DEFAULT), "GameRpcCodec.encode returned null");
    }
    private Object decode(ByteBuf body, Class<?> type) {
        return Objects.requireNonNull(type.cast(codec.decode(body, type)));
    }

    private final class Handler implements RpcHandler {
        public void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request) {
            ProtocolDescriptor protocol = runtime.protocols().get(ProtocolDescriptor.Type.REQUEST, request.command());
            if (protocol == null) throw new IllegalArgumentException("Unknown request command: " + request.command());
            Object message = decode(request.body(), protocol.messageType());
            runtime.dispatchRpc(sourceNodeId, sourceSlotId, request.command(), request.requestId(),
                    request.routeKey(), request.businessIdType(), request.businessId(), request.metadata(), message);
        }
        public void onResponse(int sourceNodeId, int command, RpcResponse response, RpcCallback<?> callback) {
            Completion<?> completion = (Completion<?>) callback;
            if (response.errorCode() != 0) { completion.fail(response.errorCode()); return; }
            Object value;
            try { value = decode(response.body(), completion.type); }
            catch (RuntimeException failure) { completion.fail(PROTOCOL_ERROR); return; }
            completion.onResponse(value);
        }
        public void onFail(int targetNodeId, int command, int errorCode, RpcCallback<?> callback) {
            ((Completion<?>) callback).fail(errorCode);
        }
    }

    private static final class Completion<T> implements RpcCallback<Object> {
        private final Class<?> type;
        private final RouteCallback<T> callback;
        Completion(Class<?> type, RouteCallback<T> callback) { this.type = type; this.callback = callback; }
        @SuppressWarnings("unchecked") public void onResponse(Object response) { callback.onSuccess((T) type.cast(response)); }
        void fail(int code) { callback.onFail(code); }
    }

    /** Idempotent: detaches configurers in reverse order, then closes the Node. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) detach(configurers.size(), null);
        node.close();
    }
}
