package cn.managame.demo.examples.router;

import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.router.call.*;
import cn.managame.router.node.*;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Four simulated processes: two stateless Routers and two services, each with exactly one application-owned
 * RPC Node. Every service connects to every Router; the source addresses the target by its Node ID.
 */
public final class RouterEchoExample {
    private RouterEchoExample() {}
    public static void main(String[] args) throws Exception { System.out.println(run()); }
    public static String run() throws Exception {
        Handler sourceHandler = new Handler(false), targetHandler = new Handler(true);
        GameRouter routerA = new GameRouter(new Handler(false));
        GameRouter routerB = new GameRouter(new Handler(false));
        ServiceRouting sourceRouting = new ServiceRouting(1, 11, sourceHandler, sourceHandler);
        ServiceRouting targetRouting = new ServiceRouting(2, 21, targetHandler, targetHandler);
        try (RpcNode a = node(101, routerA); RpcNode b = node(102, routerB);
             RpcNode source = node(1, sourceRouting); RpcNode target = node(2, targetRouting);
             routerA; routerB; sourceRouting; targetRouting) {
            routerA.start(a); routerB.start(b); sourceRouting.start(source); targetRouting.start(target);
            sourceHandler.routing = sourceRouting; targetHandler.routing = targetRouting;
            // Routing is an ordinary Handler. The application starts and closes each component.
            a.start(); b.start(); source.start(); target.start();
            // Every service declares and connects to every Router; Routers never connect to each other.
            for (ServiceRouting routing : new ServiceRouting[]{sourceRouting, targetRouting}) { routing.addRouter(101); routing.addRouter(102); }
            for (RpcNode service : new RpcNode[]{source, target}) {
                service.addPeer(101, a.localAddress(), 2); service.addPeer(102, b.localAddress(), 2);
            }
            // A service is reachable through a Router once it has announced itself there.
            await(() -> routerA.isAttached(2) && routerB.isAttached(2));
            sourceRouting.callNode(2, new RpcRequest(44, Unpooled.copiedBuffer("hello game-router", StandardCharsets.UTF_8)),
                    (String value) -> sourceHandler.result.offer(value));
            Object response = sourceHandler.result.poll(5, TimeUnit.SECONDS);
            if (response instanceof Throwable error) throw new IllegalStateException(error);
            if (response == null) throw new TimeoutException("Routed echo did not complete");
            return (String) response;
        }
    }
    private static RpcNode node(int id, RpcHandler handler) {
        // Routers never buffer business data and relay every service's traffic: size the write buffer for the
        // bytes in flight instead of Netty's 64 KiB default.
        return RpcNode.builder().nodeId(id).bindAddress(new InetSocketAddress("127.0.0.1", 0)).handler(handler)
                .writeBufferWaterMark(512 * 1024, 1024 * 1024).build();
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        if (!ready.getAsBoolean()) throw new IllegalStateException("Service did not attach to the Routers");
    }
    private static final class Handler implements RpcHandler, RouterHandler {
        final boolean echo;
        final BlockingQueue<Object> result = new ArrayBlockingQueue<>(1);
        ServiceRouting routing;
        Handler(boolean echo) { this.echo = echo; }
        @Override public void onRequest(int source, int slot, RpcRequest request) {
            // Unrelated direct RPC messages use this primary handler.
        }
        @Override public void onRoutedRequest(RoutedRequest incoming) {
            if (echo && incoming.request().requestId() != 0)
                routing.reply(incoming, new RpcResponse(incoming.request().requestId(), 0, null,
                        incoming.request().body().retain()));
        }
        @Override @SuppressWarnings("unchecked")
        public void onResponse(int target, int command, RpcResponse response, RpcCallback<?> callback) {
            if (response.errorCode() != 0) {
                result.offer(new IllegalStateException("Remote error: " + response.errorCode()));
                return;
            }
            ((RpcCallback<String>) callback).onResponse(response.body().toString(StandardCharsets.UTF_8));
        }
        @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
            result.offer(new IllegalStateException("Local RPC error: " + error));
        }
    }
}
