package cn.managame.spring.rpc;

import cn.managame.core.*;
import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.runtime.*;
import cn.managame.runtime.context.*;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.*;
import cn.managame.runtime.route.*;
import cn.managame.spring.runtime.*;
import io.netty.buffer.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RpcAssemblyTest {
    record Request(String text) {}
    record Response(String text) {}
    record Start(CountDownLatch done, int target, int expectedError) {}

    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent()); Files.writeString(marker, "test-only TCP fallback");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }

    @Configuration(proxyBeanMethods=false)
    @EnableGameRuntime(basePackages="cn.managame.spring.rpcfixture")
    @EnableGameRpc
    static class Config {
        @Bean RouteExecutor executor() { return RouteExecutors.virtualThreads(); }
        @Bean GameRuntimeConfigurer runtimeConfigurer(RouteExecutor executor) {
            return builder -> builder.routeDomains(List.of(RouteDomain.of(1,"role")))
                    .routeExecutors(List.of(RouteExecutorBinding.of(executor,1)));
        }
        @Bean ProtocolProvider protocols() { return registry -> {
            registry.register(Protocols.request(1,Request.class));
            registry.register(Protocols.response(1,Response.class));
            registry.bindResponse(Request.class,Response.class);
            registry.register(Protocols.request(2,Start.class));
        }; }
        @Bean GameRpcCodec codec() { return new GameRpcCodec() {
            public byte[] encode(Object message) {
                String text = message instanceof Request r ? r.text() : ((Response)message).text();
                return text.getBytes(StandardCharsets.UTF_8);
            }
            public <T> T decode(byte[] body,Class<T> type) {
                String text=new String(body,StandardCharsets.UTF_8);
                if (text.equals("invalid")) throw new IllegalArgumentException("malformed body");
                return type.cast(type==Request.class?new Request(text):new Response(text));
            }
        }; }
        @Bean Handlers handlers(ObjectProvider<GameRpc> rpc) { return new Handlers(rpc); }
    }

    @Handler(domain=1)
    static class Handlers {
        final ObjectProvider<GameRpc> rpc;
        final BlockingQueue<RpcHandlerContext> received=new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors=new LinkedBlockingQueue<>();
        Handlers(ObjectProvider<GameRpc> rpc) { this.rpc=rpc; }
        @HandlerMethod public void request(Request message) {
            RpcHandlerContext context=Contexts.current(RpcHandlerContext.class);
            received.add(context);
            if(context.requestId()!=0) rpc.getObject().reply(new Response(message.text()+"!"));
        }
        @HandlerMethod public void start(Start request) {
            Context origin=Contexts.current();
            rpc.getObject().call(request.target(),77,5,123,Metadatas.empty(),new Request("hello"),new RouteCallback<Response>() {
                public void onSuccess(Response response) {
                    try { assertSame(origin,Contexts.current()); assertEquals(0,request.expectedError()); assertEquals("hello!",response.text()); }
                    catch(Throwable error) { errors.add(error); } finally { request.done().countDown(); }
                }
                public void onFail(int code) {
                    try { assertSame(origin,Contexts.current()); assertEquals(request.expectedError(),code); }
                    catch(Throwable error) { errors.add(error); } finally { request.done().countDown(); }
                }
            });
        }
    }

    AnnotationConfigApplicationContext context() {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rpc",Map.of(
                "game.rpc.node-id",1,"game.rpc.port",0,"game.rpc.call-timeout-millis",100,
                "game.http.enabled",false)));
        context.register(Config.class); context.refresh(); return context;
    }

    @Test void managedRequestNotifyReplyAndOriginRouteCallbacks() throws Exception {
        var responses=new LinkedBlockingQueue<RpcResponse>();
        var remoteError=new java.util.concurrent.atomic.AtomicInteger();
        var invalidResponse=new java.util.concurrent.atomic.AtomicBoolean();
        var ignoreRequest=new java.util.concurrent.atomic.AtomicBoolean();
        RpcNode[] remote=new RpcNode[1];
        RpcHandler handler=new RpcHandler() {
            public void onRequest(int source,int slot,RpcRequest request) {
                if(ignoreRequest.get()) return;
                byte[] bytes=ByteBufUtil.getBytes(request.body());
                String result=invalidResponse.get()?"invalid":new String(bytes,StandardCharsets.UTF_8)+"!";
                remote[0].reply(source,slot,request.routeKey(),new RpcResponse(request.requestId(),remoteError.get(),null,
                        Unpooled.wrappedBuffer(result.getBytes(StandardCharsets.UTF_8))));
            }
            public void onResponse(int source,int command,RpcResponse response,RpcCallback<?> callback) {
                responses.add(new RpcResponse(response.requestId(),response.errorCode(),response.metadata(),
                        Unpooled.copiedBuffer(response.body())));
            }
            public void onFail(int target,int command,int code,RpcCallback<?> callback) { fail("Unexpected error "+code); }
        };
        remote[0]=RpcNode.builder().nodeId(2).bindAddress(new InetSocketAddress("127.0.0.1",0)).handler(handler).build();
        remote[0].start();
        try(var remoteNode=remote[0];var context=context()) {
            RpcNode local=context.getBean(RpcNode.class);
            local.addPeer(2,remoteNode.localAddress(),1);
            Handlers owners=context.getBean(Handlers.class);
            // Wait for the handshake via an actual Notify, without exposing Slot internals.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(remoteNode.notify(1,new RpcRequest(1,77,5,123,null,
                    Unpooled.wrappedBuffer("notice".getBytes(StandardCharsets.UTF_8))))!=cn.managame.rpc.transport.RpcSendStatus.ACCEPTED) {
                assertTrue(System.nanoTime()<deadline); Thread.sleep(5);
            }
            RpcHandlerContext incoming=owners.received.poll(5,TimeUnit.SECONDS);
            assertNotNull(incoming); assertEquals(77,incoming.routeKey()); assertEquals(5,incoming.businessIdType());
            assertEquals(123,incoming.businessId()); assertEquals(2,incoming.sourceNodeId()); assertEquals(0,incoming.requestId());
            assertThrows(IllegalArgumentException.class,()->context.getBean(GameRpc.class).reply(incoming,new Response("x")));
            remoteNode.call(1,new RpcRequest(1,77,5,123,null,Unpooled.wrappedBuffer("hello".getBytes(StandardCharsets.UTF_8))),value->{});
            RpcResponse response=responses.poll(5,TimeUnit.SECONDS); assertNotNull(response);
            try { assertEquals("hello!",response.body().toString(StandardCharsets.UTF_8)); } finally { response.body().release(); }
            GameRuntime runtime=context.getBean(GameRuntime.class);
            CountDownLatch done=new CountDownLatch(2);
            runtime.dispatch(new DefaultHandlerContext(1,99,new Start(done,2,0)));
            runtime.dispatch(new DefaultHandlerContext(1,99,new Start(done,3,cn.managame.rpc.error.RpcErrorCodes.PEER_NOT_FOUND)));
            assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(owners.errors.isEmpty(),owners.errors.toString());
            remoteError.set(cn.managame.rpc.error.RpcErrorCodes.HANDLER_ERROR);
            CountDownLatch failed=new CountDownLatch(1);
            runtime.dispatch(new DefaultHandlerContext(1,99,new Start(failed,2,remoteError.get())));
            assertTrue(failed.await(5,TimeUnit.SECONDS));
            remoteError.set(0); invalidResponse.set(true);
            CountDownLatch malformed=new CountDownLatch(1);
            runtime.dispatch(new DefaultHandlerContext(1,99,new Start(malformed,2,cn.managame.rpc.error.RpcErrorCodes.PROTOCOL_ERROR)));
            assertTrue(malformed.await(5,TimeUnit.SECONDS));
            CountDownLatch rejected=new CountDownLatch(1);
            runtime.dispatch(new DefaultHandlerContext(1,99,new Start(rejected,0,cn.managame.rpc.error.RpcErrorCodes.UNAVAILABLE)));
            assertTrue(rejected.await(5,TimeUnit.SECONDS));
            assertTrue(owners.errors.isEmpty(),owners.errors.toString());
            remoteNode.call(1,new RpcRequest(999,77,0,0,null,null),value->{});
            RpcResponse bad=responses.poll(5,TimeUnit.SECONDS); assertNotNull(bad);
            try { assertEquals(cn.managame.rpc.error.RpcErrorCodes.HANDLER_ERROR,bad.errorCode()); } finally { bad.body().release(); }
            ignoreRequest.set(true);
            CountDownLatch timeout=new CountDownLatch(1);
            runtime.dispatch(new DefaultHandlerContext(1,99,new Start(timeout,2,cn.managame.rpc.error.RpcErrorCodes.TIMEOUT)));
            context.close();
            assertEquals(0,timeout.getCount(),"Graceful close must drain the registered RPC timeout continuation");
            assertTrue(owners.errors.isEmpty(),owners.errors.toString());
        }
    }

    @Test void closeReleasesManagedPortAndDisallowsRestart() throws Exception {
        var context=context(); RpcNode node=context.getBean(RpcNode.class);
        InetSocketAddress address=(InetSocketAddress)node.localAddress();
        context.close(); assertThrows(IllegalStateException.class,node::start);
        try(var socket=new java.net.ServerSocket()) { socket.bind(address); }
    }
}
