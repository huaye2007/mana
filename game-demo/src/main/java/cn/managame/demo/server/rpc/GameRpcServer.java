package cn.managame.demo.server.rpc;

import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import cn.managame.demo.protocol.CommandBinding;
import static cn.managame.demo.protocol.rpc.RpcProtocol.TRACE_ID;

import cn.managame.demo.serialization.MessageSerializer;
import cn.managame.demo.server.runtime.ServerRuntime;
import cn.managame.demo.server.support.CommandMetadata;
import cn.managame.demo.server.support.GameMessages;
import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;
import cn.managame.rpc.core.*;
import cn.managame.runtime.route.Route;
import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


/** Internal RPC transport, protocol dispatch and typed calls. */
public final class GameRpcServer implements AutoCloseable {
    private final RpcNode rpc;
    private final Map<Integer, CommandBinding<?, ?>> routes;
    private final ServerRuntime runtime;
    private final RpcRuntimeClient calls;
    private final GameMessages.Sender sender = new GameMessages.Sender() {
        @Override public boolean send(Connection connection, int requestId, int commandId, Object message) {
            var binding = routes.get(commandId);
            if (binding == null) throw new IllegalArgumentException("Unknown response command: " + commandId);
            ByteBuf body = MessageSerializer.serializeBuffer(binding.responseType().cast(message));
            try { return rpc.reply(connection, new RpcResponse(requestId, 0, RpcMetadata.EMPTY, body)); }
            finally { body.release(); }
        }
        @Override public boolean sendError(Connection connection, int requestId, int commandId, int code, String... args) {
            return rpc.reply(connection, RpcResponse.error(requestId, RpcError.fromCode(code), args));
        }
    };

    public GameRpcServer(int nodeId, int port, ServerRuntime runtime, Collection<CommandBinding<?, ?>> routes) {
        this.routes = routes.stream().collect(Collectors.toUnmodifiableMap(CommandBinding::id, binding -> binding));
        for (var binding : this.routes.values()) {
            if (binding.id() >= 0 || !binding.equals(runtime.command(binding.id())))
                throw new IllegalArgumentException("RPC route does not match runtime command: " + binding.id());
        }
        this.runtime = runtime;
        rpc = RpcNode.builder().nodeId(nodeId).listen("127.0.0.1", port)
                .defaultTimeout(Duration.ofSeconds(3)).handler(this::receive).build();
        calls = new RpcRuntimeClient(runtime.engine(), rpc);
    }

    private void receive(Connection connection, Object message) {
        if (!(message instanceof RpcRequest request) || request.requestId() == 0) return;
        GameMessages.bind(connection, sender);
        var binding = routes.get(request.command());
        if (binding == null) {
            sender.sendError(connection, request.requestId(), request.command(), RpcError.NO_HANDLER.code());
            return;
        }
        Object command;
        long traceId;
        try {
            if (request.routeKey() <= 0) throw new IllegalArgumentException("Player routeKey must be positive");
            command = MessageSerializer.deserialize(request.body(), binding.requestType());
            traceId = request.metadata().contains(TRACE_ID) ? request.metadata().getLong(TRACE_ID) : 0;
        } catch (IllegalArgumentException failure) {
            sender.sendError(connection, request.requestId(), request.command(), RpcError.PROTOCOL_ERROR.code());
            return;
        }
        runtime.submit(request.command(), command, connection, request.requestId(),
                CommandMetadata.player(request.routeKey(), traceId));
    }

    public void connectPeer(int nodeId, InetSocketAddress address) throws Exception {
        CompletableFuture<Void> connected = new CompletableFuture<>();
        rpc.connect(nodeId, address.getHostString(), address.getPort(), new ConnectCallback() {
            @Override public void onSuccess(Connection connection) { connected.complete(null); }
            @Override public void onFailure(Throwable failure) { connected.completeExceptionally(failure); }
        });
        connected.get(5, TimeUnit.SECONDS);
    }

    public <Q, S> CompletableFuture<S> call(int peer, CommandBinding<Q, S> binding, Q request,
                                           Route callbackRoute, RpcOptions options) {
        return calls.call(peer, binding, request, callbackRoute, options);
    }

    public void stopCalls() { calls.close(); }
    public void start() { rpc.start(); }
    public int nodeId() { return rpc.nodeId(); }
    public InetSocketAddress address() { return rpc.localAddress(); }
    @Override public void close() {
        try { stopCalls(); }
        finally { rpc.close(); }
    }
}
