package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataKey;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteKeyResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class BindingAndEventTest {
    record RoleId(long value) {}
    record UseItemReq(int count) {}
    record UseItemRes(int count) {}
    record Changed(long roleId) {}
    record Other(long key) {}
    static final MetadataKey<String> TRACE = MetadataKey.application(200, String.class);
    static class EventRoute implements RouteKeyResolver<Changed> { public long resolve(Changed e) { return e.roleId(); } }
    static class WrongRoute implements RouteKeyResolver<Other> { public long resolve(Other e) { return e.key(); } }
    static class ThrowingRoute implements RouteKeyResolver<Changed> { public long resolve(Changed e) { throw new IllegalStateException("route"); } }

    static ProtocolRegistry protocols() {
        return ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, UseItemReq.class)
                .register(1, ProtocolType.RESPONSE, UseItemRes.class).response(UseItemReq.class, UseItemRes.class).build();
    }
    @Handler(routeType = TestRoutes.Player.class)
    static class Commands {
        RoleId role;
        UseItemReq request;
        HandlerContext context;
        @HandlerMethod public void use(RoleId role, UseItemReq request) {
            this.role = role; this.request = request; context = HandlerContexts.current();
        }
    }
    @Handler(routeType = TestRoutes.Player.class)
    static class HiddenSource {
        long key;
        @HandlerMethod public void use(UseItemReq request) { key = HandlerContexts.current().routeKey(); }
    }
    static GameRuntime.Builder commandBuilder(AtomicInteger resolves) {
        return builder().protocols(protocols()).parameters(ParameterResolverRegistry.builder()
                .registerRouteSource(RoleId.class, inv -> {
                    resolves.incrementAndGet(); return new RoleId((Long) inv.connection());
                }).build()).defaultRoute(TestRoutes.Player.class, RoleId.class, RoleId::value);
    }

    @Test void commandBindsOnceAndSharesSemanticValueWithRoute() throws Exception {
        Commands commands = new Commands(); AtomicInteger resolved = new AtomicInteger();
        try (GameRuntime runtime = commandBuilder(resolved).handler(commands).build()) {
            UseItemReq request = new UseItemReq(2);
            done(runtime.command(new CommandHandlerInvocation(request, 33L, 41, Metadata.empty().with(TRACE, "trace"))));
            assertEquals(1, resolved.get()); assertEquals(new RoleId(33), commands.role);
            assertSame(request, commands.request); assertEquals(33, commands.context.routeKey());
            assertEquals("trace", commands.context.metadata().get(TRACE));
            CommandContext context = assertInstanceOf(CommandContext.class, commands.context);
            assertEquals(41, context.requestId());
            assertEquals(1, context.metadata().size());
            assertEquals(33L, context.connection()); assertEquals(protocols().require(UseItemReq.class), context.command());
            assertTrue(runtime.commands().contains(UseItemReq.class));
        }
    }

    @Test void requestCorrelationIsExplicitAndUncorrelatedCommandsDefaultToZero() throws Exception {
        var handler = new Commands();
        try (var runtime = commandBuilder(new AtomicInteger()).handler(handler).build()) {
            var request = new UseItemReq(1);
            done(runtime.command(request, 33L, Integer.MAX_VALUE));
            var correlated = (CommandContext) handler.context;
            assertEquals(Integer.MAX_VALUE, correlated.requestId());
            assertEquals(Integer.MAX_VALUE, correlated.invocation().requestId());
            assertSame(Metadata.empty(), correlated.metadata());

            done(runtime.command(request, 33L));
            assertEquals(0, ((CommandContext) handler.context).requestId());
            assertEquals(Integer.MAX_VALUE, correlated.requestId());

            var oneWay = new CommandHandlerInvocation(request, 33L, Metadata.empty());
            done(runtime.command(oneWay));
            assertEquals(0, ((CommandContext) handler.context).requestId());
            assertEquals(0, new CommandContext(new Route(TestRoutes.Player.class, 33),
                    Metadata.empty(), protocols().require(UseItemReq.class), 33L).requestId());
            assertThrows(IllegalArgumentException.class,
                    () -> new CommandHandlerInvocation(request, 33L, -1, Metadata.empty()));
        }
    }

    @Test void routeSourceNeedNotBeAMethodArgument() throws Exception {
        HiddenSource handler = new HiddenSource(); AtomicInteger resolves = new AtomicInteger();
        try (GameRuntime runtime = commandBuilder(resolves).handler(handler).build()) {
            done(runtime.command(new UseItemReq(1), Long.MAX_VALUE));
            assertEquals(Long.MAX_VALUE, handler.key); assertEquals(1, resolves.get());
        }
    }

    @Test void protocolIdentityAndExplicitRelationshipsAreUnambiguous() {
        ProtocolRegistry p = protocols();
        assertEquals(UseItemReq.class, p.require(ProtocolType.REQUEST, 1).messageType());
        assertEquals(UseItemRes.class, p.require(ProtocolType.RESPONSE, 1).messageType());
        assertEquals(UseItemRes.class, p.responseTo(UseItemReq.class).orElseThrow().messageType());
        assertEquals(p.responseTo(UseItemReq.class), p.responseTo(p.require(UseItemReq.class)));
        ProtocolRegistry distinct = ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, UseItemReq.class)
                .register(2, ProtocolType.RESPONSE, UseItemRes.class).response(UseItemReq.class, UseItemRes.class).build();
        assertEquals(2, distinct.responseTo(UseItemReq.class).orElseThrow().id());
        assertThrows(IllegalArgumentException.class, () -> p.require(ProtocolType.NOTIFY, 1));
        assertThrows(IllegalArgumentException.class, () -> ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, UseItemReq.class)
                .register(1, ProtocolType.REQUEST, UseItemRes.class));
        assertThrows(IllegalArgumentException.class, () -> ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, UseItemReq.class)
                .response(UseItemReq.class, UseItemRes.class).build());
        assertThrows(IllegalArgumentException.class, () -> ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, UseItemReq.class)
                .register(2, ProtocolType.NOTIFY, UseItemRes.class).response(UseItemReq.class, UseItemRes.class).build());
    }

    @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = EventRoute.class)
    static class Events {
        final List<String> order; final AtomicReference<HandlerContext> outer;
        Events(List<String> order, AtomicReference<HandlerContext> outer) { this.order = order; this.outer = outer; }
        @EventMethod(order = -1) public void first(Changed e) {
            assertEquals(e.roleId(), HandlerContexts.current().routeKey());
            if (outer.get() != null) {
                assertNotSame(outer.get(), HandlerContexts.current());
                assertEquals("child", HandlerContexts.current().metadata().get(TRACE));
            }
            order.add("first"); throw new IllegalStateException("isolated");
        }
        @EventMethod(order = 2) public void last(Changed e) { order.add("last"); }
    }
    @EventHandler(routeType = TestRoutes.Guild.class, routeKeyResolver = EventRoute.class)
    static class RemoteEvents {
        final List<String> order;
        RemoteEvents(List<String> order) { this.order = order; }
        @EventMethod(order = 0) public void middle(Changed e) { order.add("remote"); }
    }
    @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = ThrowingRoute.class)
    static class BadRouteEvents {
        @EventMethod(order = -2) public void changed(Changed e) { fail("Route resolution should fail"); }
    }

    @Test void inlineEventsRestoreContextAndRemoteEventsQueueWithoutWaiting() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>(); List<HandlerException> failures = new CopyOnWriteArrayList<>();
        AtomicReference<HandlerContext> outer = new AtomicReference<>();
        try (GameRuntime runtime = builder().eventType(Changed.class).routeKeyResolver(Changed.class, new EventRoute())
                .routeKeyResolver(Changed.class, new ThrowingRoute())
                .handler(new Events(order, outer)).handler(new RemoteEvents(order)).handler(new BadRouteEvents())
                .exceptionHandler(failures::add).metadataPropagator(m -> m.with(TRACE, "child")).build()) {
            CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
            runtime.dispatch(TestRoutes.Guild.class, 5, () -> { blocked.countDown(); await(release); });
            try {
                await(blocked);
                done(runtime.dispatch(TestRoutes.Player.class, 5, () -> {
                    HandlerContext context = HandlerContexts.current(); outer.set(context);
                    order.add("start"); runtime.publish(new Changed(5));
                    assertSame(context, HandlerContexts.current()); order.add("end");
                }));
                assertEquals(List.of("start", "first", "last", "end"), order);
            } finally { release.countDown(); }
            done(runtime.dispatch(TestRoutes.Guild.class, 5, () -> {}));
            assertEquals(List.of("start", "first", "last", "end", "remote"), order);
            assertEquals(2, failures.size());
        }
    }

    @Test void eventsOutsideRuntimeAreQueuedAndSubscribersStayOrdered() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = builder().eventType(Changed.class).routeKeyResolver(Changed.class, new EventRoute())
                .handler(new Events(order, new AtomicReference<>())).build()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            runtime.dispatch(TestRoutes.Player.class, 1, () -> { entered.countDown(); await(release); });
            try { await(entered); runtime.publish(new Changed(1)); assertTrue(order.isEmpty()); }
            finally { release.countDown(); }
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            assertEquals(List.of("first", "last"), order);
        }
    }

    @Test void resolutionFailureUsesUnifiedBoundary() {
        List<HandlerException> errors = new ArrayList<>();
        try (GameRuntime runtime = commandBuilder(new AtomicInteger()).handler(new Commands()).exceptionHandler(errors::add).build()) {
            assertThrows(HandlerException.class, () -> runtime.command(new UseItemReq(1), "invalid connection"));
            assertEquals(1, errors.size());
        }
    }

    @Test void missingAndDuplicateResolversFailBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> builder().protocols(protocols()).handler(new Commands())
                .defaultRoute(TestRoutes.Player.class, RoleId.class, RoleId::value).build());
        assertThrows(IllegalArgumentException.class, () -> commandBuilder(new AtomicInteger()).handler(new Commands()).handler(new Commands()).build());
        assertThrows(IllegalArgumentException.class, () -> ParameterResolverRegistry.builder()
                .registerRouteSource(RoleId.class, inv -> new RoleId(1))
                .registerRouteSource(RoleId.class, inv -> new RoleId(1)));
        assertThrows(IllegalArgumentException.class, () -> ParameterResolverRegistry.builder()
                .register(RoleId.class, inv -> new RoleId(1))
                .registerRouteSource(RoleId.class, inv -> new RoleId(1)));
        assertThrows(IllegalArgumentException.class, () -> builder().protocols(protocols()).handler(new HiddenSource()).build());
    }

    @Handler(routeType = TestRoutes.Player.class) static class BadReturn {
        @HandlerMethod public int use(UseItemReq request) { return 1; }
    }
    @Handler(routeType = TestRoutes.Player.class) static class ContextParameter {
        @HandlerMethod public void use(UseItemReq request, HandlerContext context) {}
    }
    @Handler(routeType = TestRoutes.Player.class) static class PrivateMethod {
        @HandlerMethod private void use(UseItemReq request) {}
    }
    @Handler(routeType = TestRoutes.Player.class) static class StaticMethod {
        @HandlerMethod public static void use(UseItemReq request) {}
    }
    @Handler(routeType = TestRoutes.Player.class) static class NoRequest {
        @HandlerMethod public void use(RoleId role) {}
    }
    @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = EventRoute.class) static class BadEventCount {
        @EventMethod public void changed(Changed e, RoleId id) {}
    }
    @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = EventRoute.class) static class NotEvent {
        @EventMethod public void changed(UseItemReq e) {}
    }
    @EventHandler(routeType = TestRoutes.Player.class, routeKeyResolver = WrongRoute.class) static class WrongEventSource {
        @EventMethod public void changed(Changed e) {}
    }
    static class InvalidCron { @Cron(value = "invalid", routeType = TestRoutes.Guild.class, routeKey = 1) public void run() {} }
    static class BadCronParameters { @Cron(value = "* * * * * ?", routeType = TestRoutes.Guild.class, routeKey = 1) public void run(RoleId id) {} }

    @Test void malformedHandlersEventsAndCronsFailAtInitialization() {
        for (Object invalid : List.of(new BadReturn(), new ContextParameter(), new PrivateMethod(), new StaticMethod(),
                new NoRequest(), new BadEventCount(), new NotEvent(), new WrongEventSource(), new InvalidCron(), new BadCronParameters())) {
            assertThrows(IllegalArgumentException.class, () -> commandBuilder(new AtomicInteger()).eventType(Changed.class)
                    .routeKeyResolver(Changed.class, new EventRoute()).routeKeyResolver(Other.class, new WrongRoute())
                    .handler(invalid).build(), invalid.getClass().getName());
        }
    }

    @Test void metadataIsImmutableTypedAndChecksIdBoundaries() {
        var number = MetadataKey.application(65535, Long.class);
        Metadata initial = Metadata.empty().with(TRACE, "one").with(number, Long.MIN_VALUE);
        Metadata child = initial.with(TRACE, "two");
        assertEquals("one", initial.get(TRACE)); assertEquals("two", child.get(TRACE));
        assertEquals(Long.MIN_VALUE, child.get(number)); assertEquals(2, child.size());
        assertThrows(IllegalArgumentException.class, () -> MetadataKey.application(199, String.class));
        assertThrows(IllegalArgumentException.class, () -> new MetadataKey<>(65536, String.class));
        assertThrows(IllegalArgumentException.class, () -> new MetadataKey<>(-1, String.class));
        assertThrows(IllegalArgumentException.class, () -> new MetadataKey<>(201, Boolean.class));
        assertThrows(IllegalArgumentException.class, () -> initial.get(new MetadataKey<>(200, Long.class)));
        assertThrows(IllegalArgumentException.class, () -> initial.with(new MetadataKey<>(200, Long.class), 1L));
    }
}
