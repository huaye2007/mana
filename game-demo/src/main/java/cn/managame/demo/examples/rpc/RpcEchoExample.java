package cn.managame.demo.examples.rpc;

import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.GlobalEventExecutor;
import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Two real TCP nodes, application-owned decoding, and explicit borrowed-buffer retention. */
public final class RpcEchoExample {
    private RpcEchoExample() {}
    public static void main(String[] args) throws Exception {
        System.out.println(run());
    }
    public static String run() throws Exception {
        EchoHandler serverHandler = new EchoHandler();
        ClientHandler clientHandler = new ClientHandler();
        try (RpcNode server = RpcNode.builder().nodeId(1).bindAddress(new InetSocketAddress("127.0.0.1", 0))
                    .handler(serverHandler).build();
             RpcNode client = RpcNode.builder().nodeId(2).bindAddress(new InetSocketAddress("127.0.0.1", 0))
                    .handler(clientHandler).build()) {
            serverHandler.node = server;
            server.start();
            client.start();
            client.addPeer(1, server.localAddress(), 2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (client.notify(1, new RpcRequest(1, null)) != RpcSendStatus.ACCEPTED) {
                if (System.nanoTime() >= deadline) throw new TimeoutException("RPC peer did not become ready");
                Thread.sleep(10);
            }
            Promise<String> result = clientHandler.result;
            client.call(1, new RpcRequest(2,
                    Unpooled.copiedBuffer("hello game-rpc", StandardCharsets.UTF_8)),
                    (String text) -> result.trySuccess(text));
            return result.get(5, TimeUnit.SECONDS);
        }
    }
    private static final class EchoHandler implements RpcHandler {
        RpcNode node;
        public void onRequest(int source, int slot, RpcRequest request) {
            if (request.requestId() == 0) return;
            // Incoming body is borrowed. Transfer a retained reference to reply().
            node.reply(source, slot, request.routeKey(),
                    new RpcResponse(request.requestId(), 0, null, request.body().retain()));
        }
        public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {}
        public void onFail(int target, int command, int error, RpcCallback<?> callback) {}
    }
    private static final class ClientHandler implements RpcHandler {
        final Promise<String> result = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        public void onRequest(int source, int slot, RpcRequest request) {}
        @SuppressWarnings("unchecked")
        public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
            if (response.errorCode() != 0) {
                result.tryFailure(new IllegalStateException("Remote error: " + response.errorCode()));
                return;
            }
            // This application binds command 2 to a String response.
            ((RpcCallback<String>) callback).onResponse(response.body().toString(StandardCharsets.UTF_8));
        }
        public void onFail(int target, int command, int error, RpcCallback<?> callback) {
            result.tryFailure(new IllegalStateException("Local RPC error: " + error));
        }
    }
}

