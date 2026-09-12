package cn.managame.rpc;

import cn.managame.network.*;

import io.netty.buffer.ByteBuf;

import java.util.*;

/** One message path for encoding, sending and dispatch of verified RPC messages. */
final class RpcMessages {
    private final RpcConnections connections;
    private final RpcCalls calls;
    private final RpcTransport transport;
    private final RpcCodecHandler codecHandler;
    private final RpcLimits limits;
    private final RpcHandler handler;
    private final RpcObserver observer;

    RpcMessages(
            RpcConnections connections,
            RpcCalls calls,
            RpcTransport transport,
            RpcCodecHandler codecHandler,
            RpcLimits limits,
            RpcHandler handler,
            RpcObserver observer) {
        this.connections = connections;
        this.calls = calls;
        this.transport = transport;
        this.codecHandler = codecHandler;
        this.limits = limits;
        this.handler = handler;
        this.observer = observer;
    }

    boolean accepts(ByteBuf frame) {
        return frame.isReadable() && frame.readableBytes() <= limits.maxMessageBytes();
    }

    RpcMessage decode(ByteBuf frame) {
        return codecHandler.decode(frame);
    }

    void receive(Connection connection, RpcPeer peer, RpcMessage message) {
        if (message instanceof RpcRequest request)
            deliver(connection, peer.nodeId(), request.requestId(), request);
        else if (message instanceof RpcResponse response) calls.receiveResponse(peer, response);
        else deliver(connection, peer.nodeId(), null, message);
    }

    private void validateRequest(int command, ByteBuf body, RpcOptions options) {
        if (command <= 0) throw new IllegalArgumentException("command must be positive");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(options, "options");
        RpcMetadataUtil.validateSize(options.metadata(), limits.maxMetadataBytes());
    }

    void call(int target, int command, ByteBuf body, RpcOptions options, RpcCallback callback) {
        Objects.requireNonNull(callback);
        validateRequest(command, body, options);
        long deadline = calls.deadline(options.timeout());
        RpcPeer peer = null;
        RpcFuture pending = null;
        try {
            peer = connections.requirePeer(target);
            pending = calls.begin(peer, deadline, callback);
            if (pending.isDone()) return;
            sendMessage(
                    peer,
                    options.routeKey(),
                    new RpcRequest(command, pending.requestId, options, body),
                    pending);
        } catch (Exception e) {
            observer.observe(
                    "call-submit-failed",
                    target,
                    pending == null ? null : pending.requestId,
                    null,
                    e);
            var error = RpcCalls.localError(e);
            if (pending != null) calls.fail(peer, pending, error);
            else calls.reject(target, callback, error);
        }
    }

    private ByteBuf encodeMessage(RpcMessage message) {
        try {
            return codecHandler.encode(message);
        } catch (Exception failure) {
            if (!(message instanceof RpcResponse response) || response.errorCode() != 0)
                throw failure;
            observer.observe("response-encode-failed", failure);
            return codecHandler.encode(
                    new RpcResponse(
                            response.requestId(),
                            RpcError.INTERNAL_ERROR.code(),
                            RpcMetadata.EMPTY,
                            null));
        }
    }

    boolean send(int target, int command, ByteBuf body, RpcOptions options) {
        validateRequest(command, body, options);
        return send(target, new RpcRequest(command, 0, options, body), options);
    }

    boolean send(int target, RpcMessage message, RpcOptions options) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(options, "options");
        if (message instanceof RpcHandshake || message instanceof RpcHeartbeat)
            throw new IllegalArgumentException("Connection control is managed by RpcNode");
        try {
            return sendMessage(connections.requirePeer(target), options.routeKey(), message, null)
                    == RpcTransport.Submission.ACCEPTED;
        } catch (Exception e) {
            observer.observe("send-rejected", target, null, null, e);
            return false;
        }
    }

    boolean reply(Connection connection, RpcMessage message) {
        Objects.requireNonNull(connection);
        if (!(message instanceof RpcResponse || message instanceof RpcRouteMessage))
            throw new IllegalArgumentException("Expected RpcResponse or opaque envelope");
        if (message instanceof RpcResponse response && response.requestId() <= 0) {
            observer.observe("send-reply-rejected", null, response.requestId(), connection, null);
            return false;
        }
        try {
            return sendOnConnection(connection, message) == RpcTransport.Submission.ACCEPTED;
        } catch (Exception e) {
            observer.observe("reply-failed", null, null, connection, e);
            return false;
        }
    }

    private void deliver(Connection connection, int source, Integer id, RpcMessage message) {
        try {
            handler.handleUserMsg(connection, message);
        } catch (Throwable e) {
            observer.observe("handler-failed", source, id, connection, e);
        }
    }

    void protocolFailure(Connection connection, RpcPeer peer, Exception failure) {
        if (!(failure instanceof RpcProtocolException e) || e.requestId() <= 0) return;
        if (!e.isResponse())
            reply(
                    connection,
                    new RpcResponse(
                            e.requestId(),
                            RpcError.PROTOCOL_ERROR.code(),
                            RpcMetadata.EMPTY,
                            null));
        else calls.protocolFailure(peer, e.requestId());
    }

    private RpcTransport.Submission sendMessage(
            RpcPeer peer, long key, RpcMessage message, RpcFuture pending) {
        if (pending != null && (pending.isDone() || calls.expireCall(peer, pending)))
            return RpcTransport.Submission.UNAVAILABLE;
        connections.preflight(peer, key);
        ByteBuf frame = encodeMessage(message);
        try {
            var connection = connections.selectOriginating(peer, key);
            return pending == null
                    ? transport.write(connection, frame)
                    : calls.submit(peer, pending, connection, frame);
        } finally {
            frame.release();
        }
    }

    RpcTransport.Submission sendOnConnection(Connection connection, RpcMessage message) {
        return sendOnConnection(connection, message, null);
    }

    RpcTransport.Submission sendOnConnection(
            Connection connection, RpcMessage message, Runnable beforeSubmit) {
        if (!(message instanceof RpcHandshake || message instanceof RpcHeartbeat))
            connection = connections.selectReply(connection);
        ByteBuf frame = encodeMessage(message);
        try {
            if (!(message instanceof RpcHandshake || message instanceof RpcHeartbeat))
                connection = connections.selectReply(connection);
            if (beforeSubmit != null) beforeSubmit.run();
            return transport.write(connection, frame);
        } finally {
            frame.release();
        }
    }
}
