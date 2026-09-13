package cn.managame.demo.server.rpc;

import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;

import cn.managame.demo.server.DemoServer;
import cn.managame.demo.serialization.MessageSerializer;

import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;
import cn.managame.rpc.core.*;
import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static cn.managame.demo.protocol.rpc.RpcProtocol.*;

/** RPC callbacks decode their borrowed response before completing the application's future. */
public final class InternalRpcClient implements AutoCloseable {
    private final RpcNode rpc = RpcNode.builder().nodeId(10).listen("127.0.0.1", 0)
            .handler((connection, message) -> {})
            .defaultTimeout(Duration.ofSeconds(3)).build();

    public void connect(InetSocketAddress address) throws Exception {
        rpc.start();
        CompletableFuture<Void> connected = new CompletableFuture<>();
        rpc.connect(DemoServer.NODE_ID, address.getHostString(), address.getPort(), new ConnectCallback() {
            @Override public void onSuccess(Connection connection) { connected.complete(null); }
            @Override public void onFailure(Throwable failure) { connected.completeExceptionally(failure); }
        });
        // Wait for the handshake rather than sleeping or sending before the peer is ready.
        connected.get(5, TimeUnit.SECONDS);
    }

    public CompletableFuture<GrantGoldRes> grant(long playerId, int amount, long traceId) {
        ByteBuf body = MessageSerializer.serializeBuffer(new GrantGoldReq(amount));
        try {
            return call(GRANT_GOLD, body, RpcOptions.builder().routeKey(playerId).putLong(TRACE_ID, traceId).build());
        } finally {
            body.release();
        }
    }

    // Package-private for wire-boundary integration tests; the caller owns body.
    CompletableFuture<GrantGoldRes> call(int command, ByteBuf body, RpcOptions options) {
        CompletableFuture<GrantGoldRes> future = new CompletableFuture<>();
        try {
            rpc.call(DemoServer.NODE_ID, command, body, options, result -> {
                try {
                    if (result.isSuccess()) future.complete(MessageSerializer.deserialize(result.value().body(), GrantGoldRes.class));
                    else future.completeExceptionally(new CallFailure(result.error(),
                            result.value() == null ? List.of() : List.of(result.value().errorArgs())));
                } catch (RuntimeException failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    public static final class CallFailure extends RuntimeException {
        private final RpcError error;
        private final List<String> errorArgs;

        CallFailure(RpcError error, List<String> errorArgs) {
            super("RPC error " + error.code() + ", args=" + errorArgs);
            this.error = error;
            this.errorArgs = List.copyOf(errorArgs);
        }

        public RpcError error() { return error; }
        public List<String> errorArgs() { return errorArgs; }
    }

    @Override public void close() { rpc.close(); }
}
