package cn.managame.runtime;

import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.context.EventContext;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.InvocationContext;
import cn.managame.runtime.context.RouteCallContext;
import cn.managame.runtime.context.TimerContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolProvider;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;
import cn.managame.runtime.timer.Cron;
import cn.managame.runtime.timer.TimerRef;

import cn.managame.core.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.managame.core.FrameworkErrorCodes.*;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeTest {
    record Request(Runnable run) {} record Response() {}
    record Notice(int routeDomain,long routeKey) implements Event {}
    static class CustomContext extends DefaultHandlerContext {
        CustomContext(int domain,long key,Object req) { super(domain,key,req); }
    }
    @Handler(domain=1) static class Handlers {
        @HandlerMethod public void request(HandlerContext context,Request request) { assertSame(context,Contexts.current()); request.run().run(); }
    }
    @Handler(domain=1) static class CustomHandlers {
        @HandlerMethod public void request(Request request,CustomContext context) { assertSame(context,Contexts.current()); request.run().run(); }
    }
    static ProtocolProvider protocols=reg -> {
        reg.register(Protocols.request(1,Request.class)); reg.register(Protocols.response(1,Response.class)); reg.bindResponse(Request.class,Response.class);
    };
    private final List<GameRuntime> runtimes=new ArrayList<>();
    private final List<RuntimeError> errors=new CopyOnWriteArrayList<>();
    GameRuntimeBuilder builder(RouteExecutor executor) {
        return GameRuntimeBuilder.builder().routeDomains(List.of(RouteDomain.of(1,"one"),RouteDomain.of(2,"two")))
            .routeExecutors(List.of(RouteExecutorBinding.of(executor,1,2))).protocols(List.of(protocols))
            .handlers(List.of(new Handlers())).errorHandler(errors::add);
    }
    GameRuntime track(GameRuntime runtime) { runtimes.add(runtime); return runtime; }
    @AfterEach void close() { runtimes.forEach(GameRuntime::close); }
    static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
    @Test void exactRegistriesAndBuildValidation() {
        var runtime=track(builder(RouteExecutors.virtualThreads()).routeKeys(List.of(RouteKeyBinding.of(Request.class,_ -> 42))).build());
        assertEquals(Response.class,runtime.protocols().getResponseType(Request.class));
        assertEquals(Request.class,runtime.protocols().get(ProtocolType.REQUEST,1).messageType());
        assertEquals(Response.class,runtime.protocols().get(ProtocolType.RESPONSE,1).messageType());
        assertEquals(42,runtime.routeKeys().getRouteKey(new Request(() -> {})));
        assertEquals(0,runtime.routeKeys().getRouteKey(new Object()));
        assertThrows(IllegalArgumentException.class, () -> builder(RouteExecutors.virtualThreads()).routeExecutors(List.of()).build());
        assertThrows(IllegalArgumentException.class, () -> builder(RouteExecutors.virtualThreads()).protocols(List.of(protocols,protocols)).build());
        assertThrows(IllegalArgumentException.class, () -> builder(RouteExecutors.virtualThreads()).handlers(List.of(new Handlers(),new Handlers())).build());
        assertThrows(IllegalArgumentException.class, () -> builder(RouteExecutors.virtualThreads()).routeDomains(List.of(RouteDomain.of(1,"a"),RouteDomain.of(1,"b"))).build());
    }
    @Test void dispatchValidationAndCustomContext() throws Exception {
        var runtime=track(builder(RouteExecutors.virtualThreads()).handlers(List.of(new CustomHandlers())).build());
        Request request=new Request(() -> {});
        assertEquals(HANDLER_CONTEXT_MISMATCH,assertThrows(RuntimeDispatchException.class,() -> runtime.dispatch(new DefaultHandlerContext(1,1,request))).errorCode());
        assertEquals(ROUTE_DOMAIN_MISMATCH,assertThrows(RuntimeDispatchException.class,() -> runtime.dispatch(new DefaultHandlerContext(2,1,request))).errorCode());
        assertEquals(INVALID_ROUTE_KEY,assertThrows(RuntimeDispatchException.class,() -> runtime.dispatch(new DefaultHandlerContext(1,0,request))).errorCode());
        assertEquals(HANDLER_NOT_FOUND,assertThrows(RuntimeDispatchException.class,() -> runtime.dispatch(new DefaultHandlerContext(1,1,new Object()))).errorCode());
        CountDownLatch done=new CountDownLatch(1);
        runtime.dispatch(new CustomContext(1,1,new Request(done::countDown))); await(done);
        assertTrue(errors.isEmpty()); assertNull(Contexts.currentOrNull());
    }
    @Test void crossRouteSuccessFailureAndSourceRestoration() throws Exception {
        var runtime=track(builder(RouteExecutors.platformThreads(2)).build());
        CountDownLatch done=new CountDownLatch(2);
        var metadata=Metadatas.builder().put(MetadataKeys.longKey(1),77L).build();
        HandlerContext[] source=new HandlerContext[1];
        source[0]=new DefaultHandlerContext(1,5,3,99,metadata,new Request(() -> {
            runtime.call(2,8,() -> {
                var target=Contexts.current(RouteCallContext.class);
                assertEquals(2,target.routeDomain()); assertEquals(8,target.routeKey()); assertEquals(99,target.businessId()); assertSame(metadata,target.metadata());
                return 123;
            },new RouteCallback<Integer>() {
                public void onSuccess(Integer result) { assertEquals(123,result); assertSame(source[0],Contexts.current()); done.countDown(); }
                public void onFail(int code) { fail("Unexpected "+code); }
            });
            runtime.call(2,9,() -> { throw new IllegalStateException("expected"); },new RouteCallback<Object>() {
                public void onSuccess(Object result) { fail("Unexpected success"); }
                public void onFail(int code) { assertEquals(ROUTE_CALL_EXECUTION_ERROR,code); assertSame(source[0],Contexts.current()); done.countDown(); }
            });
        }));
        runtime.dispatch(source[0]); await(done);
        assertEquals(1,errors.size()); assertEquals(ROUTE_CALL_EXECUTION_ERROR,errors.getFirst().errorCode());
    }
    @Test void sameRouteInlineEventsAreOrderedAndIsolated() throws Exception {
        List<String> order=new CopyOnWriteArrayList<>();
        class Events {
            @EventMethod(order=20) public void last(Notice event) { order.add("last"); }
            @EventMethod(order=10) public void broken(Notice event) { order.add("broken"); throw new IllegalStateException(); }
            @EventMethod public void first(Notice event) { assertInstanceOf(EventContext.class,Contexts.current()); order.add("first"); }
        }
        var runtime=track(builder(RouteExecutors.virtualThreads()).eventHandlers(List.of(new Events())).build());
        CountDownLatch done=new CountDownLatch(1);
        runtime.dispatch(new DefaultHandlerContext(1,1,new Request(() -> {
            Context original=Contexts.current();
            runtime.eventBus().publish(new Notice(1,1));
            assertSame(original,Contexts.current());
            runtime.call(1,1,() -> { assertInstanceOf(RouteCallContext.class,Contexts.current()); order.add("call"); return 0; },
                new RouteCallback<Integer>() {
                    public void onSuccess(Integer n) { assertSame(original,Contexts.current()); order.add("callback"); }
                    public void onFail(int code) { fail(); }
                });
            order.add("after"); done.countDown();
        })));
        await(done); assertEquals(List.of("first","broken","last","call","callback","after"),order);
        assertEquals(1,errors.size());
    }
    @Test void timerHasFreshContextAndCancellationIsBeforeSubmissionOnly() throws Exception {
        var runtime=track(builder(RouteExecutors.virtualThreads()).build());
        CountDownLatch done=new CountDownLatch(1);
        TimerRef cancelled=runtime.timer().schedule(1,1,Duration.ofDays(1),() -> fail());
        assertTrue(cancelled.cancel()); assertFalse(cancelled.cancel());
        TimerRef fired=runtime.timer().schedule(2,4,Duration.ZERO,() -> {
            assertInstanceOf(TimerContext.class,Contexts.current());
            assertFalse(Contexts.current() instanceof InvocationContext); done.countDown();
        });
        await(done); assertFalse(fired.cancel());
        runtime.close(); runtime.close();
        assertEquals(RUNTIME_CLOSED,assertThrows(RuntimeDispatchException.class,() -> runtime.timer().schedule(1,1,Duration.ZERO,() -> {})).errorCode());
    }
    @Test void rejectedTargetFailsOnSourceAndRejectedReturnOnlyReports() throws Exception {
        Queue<Runnable> sourceQueue=new ConcurrentLinkedQueue<>();
        AtomicBoolean rejectSource=new AtomicBoolean();
        RouteExecutor source=(d,k,t) -> { if(rejectSource.get()) return RouteExecuteStatus.OVERLOADED; sourceQueue.add(t); return RouteExecuteStatus.ACCEPTED; };
        Queue<Runnable> targetQueue=new ConcurrentLinkedQueue<>();
        RouteExecutor target=(d,k,t) -> { targetQueue.add(t); return RouteExecuteStatus.ACCEPTED; };
        var runtime=track(builder(source).routeExecutors(List.of(RouteExecutorBinding.of(source,1),RouteExecutorBinding.of(target,2))).build());
        AtomicInteger callbacks=new AtomicInteger();
        runtime.dispatch(new DefaultHandlerContext(1,1,new Request(() ->
            runtime.call(2,1,() -> 1,new RouteCallback<Integer>() {
                public void onSuccess(Integer n) { callbacks.incrementAndGet(); }
                public void onFail(int code) { callbacks.incrementAndGet(); }
            }))));
        sourceQueue.remove().run(); rejectSource.set(true); targetQueue.remove().run();
        assertEquals(0,callbacks.get()); assertEquals(ROUTE_CALLBACK_DISPATCH_FAILED,errors.getFirst().errorCode());
    }
    @Test void targetOverloadReportsFailureInOriginalContext() {
        Queue<Runnable> sourceQueue=new ArrayDeque<>();
        RouteExecutor source=(d,k,t)->{sourceQueue.add(t);return RouteExecuteStatus.ACCEPTED;};
        RouteExecutor rejecting=(d,k,t)->RouteExecuteStatus.OVERLOADED;
        var runtime=track(builder(source).routeExecutors(List.of(RouteExecutorBinding.of(source,1),RouteExecutorBinding.of(rejecting,2))).build());
        AtomicInteger callbacks=new AtomicInteger();
        runtime.dispatch(new DefaultHandlerContext(1,7,new Request(()->{
            Context original=Contexts.current();
            runtime.call(2,7,()->{fail("Rejected action ran");return 0;},new RouteCallback<Integer>(){
                public void onSuccess(Integer n){fail();}
                public void onFail(int code){assertSame(original,Contexts.current());assertEquals(ROUTE_EXECUTOR_OVERLOADED,code);callbacks.incrementAndGet();}
            });
            assertEquals(1,callbacks.get());
        })));
        sourceQueue.remove().run();assertEquals(1,callbacks.get());assertTrue(errors.isEmpty());
    }
    @Test void cronActuallyDispatchesThroughRouteExecutor() throws Exception {
        CountDownLatch fired=new CountDownLatch(1);
        class Scheduled {
            @Cron(value="* * * * * ?",domain=2,routeKey=55)
            public void tick(){assertInstanceOf(TimerContext.class,Contexts.current());assertEquals(55,Contexts.current().routeKey());fired.countDown();}
        }
        var runtime=track(builder(RouteExecutors.virtualThreads()).cronHandlers(List.of(new Scheduled())).build());
        await(fired);assertTrue(errors.isEmpty());
    }
    @Test void separateRuntimesNeverInlineOnEqualRoute() {
        Queue<Runnable> qa=new ArrayDeque<>(),qb=new ArrayDeque<>();
        var a=track(builder((d,k,t)->{qa.add(t);return RouteExecuteStatus.ACCEPTED;}).build());
        var b=track(builder((d,k,t)->{qb.add(t);return RouteExecuteStatus.ACCEPTED;}).build());
        AtomicBoolean called=new AtomicBoolean();
        a.dispatch(new DefaultHandlerContext(1,1,new Request(() -> {
            b.dispatch(new DefaultHandlerContext(1,1,new Request(() -> called.set(true))));
            assertFalse(called.get());
        })));
        qa.remove().run(); assertFalse(called.get()); qb.remove().run(); assertTrue(called.get());
    }
    @Test void sharedExecutorClosesOnceAndErrorHandlerCannotEscape() throws Exception {
        AtomicInteger closes=new AtomicInteger();
        RouteExecutor executor=new RouteExecutor() {
            public RouteExecuteStatus tryExecute(int d,long k,Runnable t) { t.run(); return RouteExecuteStatus.ACCEPTED; }
            public void close() { closes.incrementAndGet(); }
        };
        var runtime=track(builder(executor).errorHandler(_ -> { throw new IllegalStateException("expected diagnostic failure"); }).build());
        runtime.dispatch(new DefaultHandlerContext(1,1,new Request(() -> { throw new IllegalStateException(); })));
        runtime.close(); runtime.close(); assertEquals(1,closes.get());
    }
}
