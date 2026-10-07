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

/** Four simulated processes: two Routers and two services, each with exactly one application-owned RPC Node. */
public final class RouterEchoExample {
    private RouterEchoExample() {}
    public static void main(String[] args) throws Exception { System.out.println(run()); }
    public static String run() throws Exception {
        Handler sourceHandler = new Handler(false), targetHandler = new Handler(true);
        GameRouter routerA = GameRouter.forRouterNode(101, new Handler(false));
        GameRouter routerB = GameRouter.forRouterNode(102, new Handler(false));
        ServiceRouting sourceRouting = GameRouter.forServiceNode(1, sourceHandler, sourceHandler);
        ServiceRouting targetRouting = GameRouter.forServiceNode(2, targetHandler, targetHandler);
        try (RpcNode a = node(101, routerA); RpcNode b = node(102, routerB);
             RpcNode source = node(1, sourceRouting); RpcNode target = node(2, targetRouting);
             routerA; routerB; sourceRouting; targetRouting) {
            routerA.start(a); routerB.start(b); sourceRouting.start(source); targetRouting.start(target);
            sourceHandler.routing = sourceRouting; targetHandler.routing = targetRouting;
            // Routing is an ordinary Handler. The application starts and closes each component.
            a.start(); b.start(); source.start(); target.start();
            routerA.registerRouter(102); routerB.registerRouter(101);
            a.addPeer(102, b.localAddress(), 1);
            await(() -> routerA.isRouterReady(102) && routerB.isRouterReady(101));
            BlockingQueue<Integer> sourceRegistration = new ArrayBlockingQueue<>(1);
            BlockingQueue<Integer> targetRegistration = new ArrayBlockingQueue<>(1);
            sourceRouting.register(101, 1, sourceRegistration::offer);
            targetRouting.register(102, 2, targetRegistration::offer);
            source.addPeer(101, a.localAddress(), 2);
            target.addPeer(102, b.localAddress(), 2);
            accepted(sourceRegistration); accepted(targetRegistration);
            BlockingQueue<Integer> binding = new ArrayBlockingQueue<>(1);
            targetRouting.bind(1001, binding::offer);
            accepted(binding);
            // Local bind success does not promise the other Router already applied the delta.
            await(() -> routerA.resolve(2, 1001).isPresent() && routerB.serviceNodes(1).contains(1));
            sourceRouting.callBinding(2, 1001,
                    new RpcRequest(44, Unpooled.copiedBuffer("hello game-router", StandardCharsets.UTF_8)),
                    (String value) -> sourceHandler.result.offer(value));
            Object response = sourceHandler.result.poll(5, TimeUnit.SECONDS);
            if (response instanceof Throwable error) throw new IllegalStateException(error);
            if (response == null) throw new TimeoutException("Routed echo did not complete");
            return (String) response;
        }
    }
    private static RpcNode node(int id, RpcHandler handler) {
        return RpcNode.builder().nodeId(id).bindAddress(new InetSocketAddress("127.0.0.1", 0)).handler(handler).build();
    }
    private static void accepted(BlockingQueue<Integer> results) throws Exception {
        Integer error = results.poll(5, TimeUnit.SECONDS);
        if (error == null || error != 0) throw new IllegalStateException("Router control failed: " + error);
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        if (!ready.getAsBoolean()) throw new IllegalStateException("Router did not become ready");
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
        public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
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
