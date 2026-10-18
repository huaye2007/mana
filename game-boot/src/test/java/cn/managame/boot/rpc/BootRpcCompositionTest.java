package cn.managame.boot.rpc;

import cn.managame.core.Metadata;
import cn.managame.rpc.call.RpcCallback;
import cn.managame.rpc.call.RpcHandler;
import cn.managame.rpc.message.RpcRequest;
import cn.managame.rpc.message.RpcResponse;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.node.RpcNodeBuilder;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.context.RpcHandlerContext;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteDomain;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RPC-to-Runtime seam assembled by hand. Nothing here is a container: the application builds the
 * Runtime, builds an RpcNodeBuilder and constructs {@link GameRpc}, which is the whole join. A container
 * integration does the same three things with beans, so what this proves is that the seam does not
 * belong to one.
 */
class BootRpcCompositionTest {
    record Request(String text) {}
    record Response(String text) {}
    record Start(CountDownLatch done, int target, int expectedError) {}

    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "test-only TCP fallback");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    /** Text in, text out; the adapter never sees business serialization. */
    static final class TextCodec implements GameRpcCodec {
        @Override public byte[] encode(Object message) {
            return (message instanceof Request request ? request.text() : ((Response) message).text())
                    .getBytes(StandardCharsets.UTF_8);
        }
        @Override public <T> T decode(byte[] body, Class<T> type) {
            String text = new String(body, StandardCharsets.UTF_8);
            return type.cast(type == Request.class ? new Request(text) : new Response(text));
        }
    }

    @Handler(domain = 1)
    static class Handlers {
        final BlockingQueue<RpcHandlerContext> received = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        GameRpc rpc;

        @HandlerMethod public void request(Request message) {
            RpcHandlerContext context = Contexts.current(RpcHandlerContext.class);
            received.add(context);
            if (context.requestId() != 0) rpc.reply(new Response(message.text() + "!"));
        }
        /** Starts an outbound call from this Route, so the completion has a Route to come back to. */
        @HandlerMethod public void start(Start request) {
            Context origin = Contexts.current();
            rpc.call(request.target(), 77, 5, 123, Metadata.empty(), new Request("hello"),
                    new RouteCallback<Response>() {
                        @Override public void onSuccess(Response response) {
                            try {
                                assertSame(origin, Contexts.current());
                                assertEquals(0, request.expectedError());
                                assertEquals("hello!", response.text());
                            } catch (Throwable error) { errors.add(error); }
                            finally { request.done().countDown(); }
                        }
                        @Override public void onFail(int code) {
                            try {
                                assertSame(origin, Contexts.current());
                                assertEquals(request.expectedError(), code);
                            } catch (Throwable error) { errors.add(error); }
                            finally { request.done().countDown(); }
                        }
                    });
        }
    }

    /** Records which hook ran and in what order, and wraps the adapter's handler the way routing does. */
    static final class Recorder implements GameRpcConfigurer {
        final String name;
        final List<String> events;
        final BlockingQueue<Integer> wrapped = new LinkedBlockingQueue<>();
        Recorder(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public void configure(RpcNodeBuilder builder) { events.add(name + ":configure"); }
        @Override public RpcHandler decorate(RpcHandler handler) {
            events.add(name + ":decorate");
            return new RpcHandler() {
                @Override public void onRequest(int source, int slot, RpcRequest request) {
                    wrapped.add(request.command()); handler.onRequest(source, slot, request);
                }
                @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
                    handler.onResponse(source, command, response, callback);
                }
                @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
                    handler.onFail(target, command, error, callback);
                }
            };
        }
        @Override public void attach(RpcNode node) {
            events.add(name + ":attach:" + (node.localAddress() == null ? "idle" : "listening"));
        }
        @Override public void detach(RpcNode node) {
            events.add(name + ":detach:" + (node.localAddress() == null ? "idle" : "listening"));
        }
    }

    private static GameRuntime runtime(Handlers handlers) {
        return GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "role")))
                .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1)))
                .protocols(ProtocolRegistry.of(
                        ProtocolDescriptor.request(1, Request.class, Response.class),
                        ProtocolDescriptor.response(1, Response.class),
                        ProtocolDescriptor.request(2, Start.class)))
                .handlers(List.of(handlers)).build();
    }

    private static RpcNodeBuilder node(int id) {
        return RpcNode.builder().nodeId(id).bindAddress(new InetSocketAddress("127.0.0.1", 0));
    }

    @Test void anApplicationJoinsRpcToRuntimeWithNothingButTheAdapter() throws Exception {
        var responses = new LinkedBlockingQueue<RpcResponse>();
        RpcNode[] peer = new RpcNode[1];
        RpcHandler remoteHandler = new RpcHandler() {
            @Override public void onRequest(int source, int slot, RpcRequest request) {
                String echoed = new String(ByteBufUtil.getBytes(request.body()), StandardCharsets.UTF_8) + "!";
                peer[0].reply(source, slot, new RpcResponse(request.requestId(), 0, null,
                        Unpooled.wrappedBuffer(echoed.getBytes(StandardCharsets.UTF_8))));
            }
            @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
                responses.add(new RpcResponse(response.requestId(), response.errorCode(), response.metadata(),
                        Unpooled.copiedBuffer(response.body())));
            }
            @Override public void onFail(int target, int command, int code, RpcCallback<?> callback) {
                fail("Unexpected failure " + code);
            }
        };
        peer[0] = node(2).handler(remoteHandler).build();
        peer[0].start();

        var events = new ArrayList<String>();
        var handlers = new Handlers();
        var recorder = new Recorder("routing", events);
        try (var remote = peer[0]; var runtime = runtime(handlers);
             var rpc = new GameRpc(runtime, new TextCodec(), node(1), List.of(recorder))) {
            handlers.rpc = rpc;
            // Every hook ran, in order, and attach saw a Node that had not been started yet.
            assertEquals(List.of("routing:configure", "routing:decorate", "routing:attach:idle"), events);
            rpc.node().start();
            rpc.node().addPeer(2, remote.localAddress(), 1);

            // An inbound Notify reaches the Handler with its envelope intact and its Domain from the
            // Handler declaration, not from anything the transport knew.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (remote.notify(1, new RpcRequest(1, 77, 5, 123, null,
                    Unpooled.wrappedBuffer("notice".getBytes(StandardCharsets.UTF_8)))) != RpcSendStatus.ACCEPTED) {
                assertTrue(System.nanoTime() < deadline, "the peer never became writable");
                Thread.sleep(5);
            }
            RpcHandlerContext incoming = handlers.received.poll(5, TimeUnit.SECONDS);
            assertNotNull(incoming);
            assertEquals(1, incoming.routeDomain()); assertEquals(77, incoming.routeKey());
            assertEquals(5, incoming.businessIdType()); assertEquals(123, incoming.businessId());
            assertEquals(2, incoming.sourceNodeId()); assertEquals(0, incoming.requestId());
            // The decorator is outside the adapter's handler and still delegates to it.
            assertEquals(1, recorder.wrapped.poll(5, TimeUnit.SECONDS));

            // An inbound Call is answered from the Handler through the adapter's reply entry.
            remote.call(1, new RpcRequest(1, 77, 5, 123, null,
                    Unpooled.wrappedBuffer("hello".getBytes(StandardCharsets.UTF_8))), value -> {});
            RpcResponse answered = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull(answered);
            try { assertEquals("hello!", answered.body().toString(StandardCharsets.UTF_8)); }
            finally { answered.body().release(); }

            // An outbound Call started on a Route completes back on that same Route, success or failure.
            var done = new CountDownLatch(2);
            runtime.dispatch(new DefaultHandlerContext(1, 99, new Start(done, 2, 0)));
            runtime.dispatch(new DefaultHandlerContext(1, 99,
                    new Start(done, 3, cn.managame.rpc.error.RpcErrorCodes.PEER_NOT_FOUND)));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(handlers.errors.isEmpty(), handlers.errors.toString());
        }
        assertEquals("routing:detach:listening", events.getLast());
    }

    @Test void aFailedAttachDetachesWhatAttachedAndClosesTheNodeItBuilt() {
        var events = new ArrayList<String>();
        GameRpcConfigurer failing = new GameRpcConfigurer() {
            @Override public void configure(RpcNodeBuilder builder) { events.add("failing:configure"); }
            @Override public void attach(RpcNode node) { throw new IllegalStateException("attach failed"); }
        };
        try (var runtime = runtime(new Handlers())) {
            var error = assertThrows(IllegalStateException.class, () -> new GameRpc(runtime, new TextCodec(),
                    node(1), List.of(new Recorder("ok", events), failing)));
            assertEquals("attach failed", error.getMessage());
        }
        // configure ran for both before anything was built; only the configurer that attached is detached.
        assertEquals(List.of("ok:configure", "failing:configure", "ok:decorate", "ok:attach:idle", "ok:detach:idle"),
                events);
    }
}
