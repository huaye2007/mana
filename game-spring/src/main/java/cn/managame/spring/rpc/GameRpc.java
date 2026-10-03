package cn.managame.spring.rpc;

import cn.managame.core.Metadata;
import cn.managame.core.Metadatas;
import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.*;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.*;
import cn.managame.runtime.protocol.*;
import cn.managame.runtime.route.RouteCallback;
import io.netty.buffer.*;
import java.util.Objects;
import static cn.managame.rpc.error.RpcErrorCodes.*;

/** Typed RPC adapter; business methods and captured callbacks execute through Runtime Routes. */
public final class GameRpc implements AutoCloseable {
    private final GameRuntime runtime;
    private final GameRpcCodec codec;
    private final RpcNode node;

    GameRpc(GameRuntime runtime, GameRpcCodec codec, RpcNodeBuilder builder) {
        this.runtime = Objects.requireNonNull(runtime);
        this.codec = Objects.requireNonNull(codec);
        node = Objects.requireNonNull(builder).handler(new Handler()).build();
    }

    RpcNode node() { return node; }

    /** Uses the registered request/response binding. Must be called on this Runtime's Route. */
    public <T> void call(int targetNodeId, long routeKey, int businessIdType, long businessId,
                         Metadata metadata, Object request, RouteCallback<T> callback) {
        ProtocolDescriptor<?> protocol = requestProtocol(request);
        Class<?> responseType = runtime.protocols().getResponseType(request.getClass());
        if (responseType == null) throw new IllegalArgumentException("No response binding: " + request.getClass());
        Objects.requireNonNull(callback);
        ByteBuf body = encode(request);
        RouteCallback<T> captured;
        try { captured = runtime.callback(callback); }
        catch (RuntimeException | Error failure) { body.release(); throw failure; }
        Completion<T> completion = new Completion<>(responseType, captured);
        try {
            node.call(targetNodeId, new RpcRequest(protocol.command(), routeKey, businessIdType,
                    businessId, metadata, body), completion);
        } catch (RuntimeException | Error failure) {
            if (body.refCnt() > 0) body.release();
            captured.onFail(UNAVAILABLE);
            throw failure;
        }
    }

    public RpcSendStatus notify(int targetNodeId, long routeKey, int businessIdType, long businessId,
                                Metadata metadata, Object request) {
        ProtocolDescriptor<?> protocol = requestProtocol(request);
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
        ProtocolDescriptor<?> request = runtime.protocols().get(ProtocolType.REQUEST, context.command());
        Class<?> expected = request == null ? null : runtime.protocols().getResponseType(request.messageType());
        if (response == null || expected != response.getClass())
            throw new IllegalArgumentException("Response does not match the request's registered response type");
        ByteBuf body = encode(response);
        try {
            return node.reply(context.sourceNodeId(), context.sourceSlotId(), context.routeKey(),
                    new RpcResponse(context.requestId(), 0, Metadatas.empty(), body));
        } catch (RuntimeException | Error failure) {
            if (body.refCnt() > 0) body.release();
            throw failure;
        }
    }

    public RpcSendStatus replyError(RpcHandlerContext context, int errorCode) {
        requireCall(context);
        if (errorCode <= 0) throw new IllegalArgumentException("errorCode must be positive");
        return node.reply(context.sourceNodeId(), context.sourceSlotId(), context.routeKey(),
                new RpcResponse(context.requestId(), errorCode, Metadatas.empty(), null));
    }

    private void requireCall(RpcHandlerContext context) {
        Objects.requireNonNull(context);
        if (context.requestId() == 0) throw new IllegalArgumentException("Notify has no response");
    }

    private ProtocolDescriptor<?> requestProtocol(Object request) {
        Objects.requireNonNull(request);
        ProtocolDescriptor<?> protocol = runtime.protocols().get(request.getClass());
        if (protocol == null || protocol.type() != ProtocolType.REQUEST)
            throw new IllegalArgumentException("RPC input must be a registered REQUEST: " + request.getClass());
        return protocol;
    }

    private ByteBuf encode(Object message) { return Unpooled.wrappedBuffer(Objects.requireNonNull(codec.encode(message))); }
    private Object decode(ByteBuf body, Class<?> type) {
        byte[] bytes = body == null ? new byte[0] : ByteBufUtil.getBytes(body);
        return Objects.requireNonNull(type.cast(codec.decode(bytes, type)));
    }

    private final class Handler implements RpcHandler {
        public void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request) {
            ProtocolDescriptor<?> protocol = runtime.protocols().get(ProtocolType.REQUEST, request.command());
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

    @Override public void close() { node.close(); }
}
