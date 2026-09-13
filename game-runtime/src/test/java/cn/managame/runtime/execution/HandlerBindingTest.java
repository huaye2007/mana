package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

class HandlerBindingTest {
    record Id(long value) {}
    record Extra(int value) {}
    record R1() {}
    record R2() {}
    record R3() {}
    record R4() {}
    record R5() {}
    record R6() {}

    @Handler(routeType = TestRoutes.Player.class)
    static class Shapes {
        final List<Integer> calls = new ArrayList<>();
        @HandlerMethod public void one(R1 request) { calls.add(1); }
        @HandlerMethod public void two(long number, R2 request) { assertEquals(42, number); calls.add(2); }
        @HandlerMethod public void three(R3 request, Extra extra, Id id) {
            assertEquals(100, id.value()); assertEquals(7, extra.value()); calls.add(3);
        }
        @HandlerMethod public void four(R4 request, Id id, Extra first, Extra second) {
            assertSame(first, second); assertEquals(100, id.value()); calls.add(4);
        }
        @HandlerMethod public void five(R5 request, Extra first, Id id, Id repeated, Extra second) {
            assertSame(first, second); assertSame(id, repeated); calls.add(5);
        }
        @HandlerMethod public void duplicate(R6 request, Extra first, Extra second) {
            assertSame(first, second); calls.add(6);
        }
    }

    @Test void specializedAndFallbackSignaturesPreserveTypesOrderAndResolveOnce() throws Exception {
        AtomicInteger identities = new AtomicInteger(), extras = new AtomicInteger();
        ProtocolRegistry.Builder protocols = ProtocolRegistry.builder();
        List<Object> requests = List.of(new R1(), new R2(), new R3(), new R4(), new R5(), new R6());
        for (int i = 0; i < requests.size(); i++) protocols.register(i, ProtocolType.REQUEST, requests.get(i).getClass());
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(Id.class, invocation -> {
                    assertNull(HandlerContexts.currentOrNull()); identities.incrementAndGet(); return new Id(100);
                })
                .register(long.class, invocation -> 42L)
                .register(Extra.class, invocation -> {
                    assertEquals(100, HandlerContexts.current().routeKey());
                    extras.incrementAndGet(); return new Extra(7);
                }).build();
        Shapes handler = new Shapes();
        try (GameRuntime runtime = builder().protocols(protocols.build()).parameters(parameters)
                .defaultRoute(TestRoutes.Player.class, Id.class, Id::value).handler(handler).build()) {
            for (Object request : requests) done(runtime.command(request, null));
            assertEquals(List.of(1, 2, 3, 4, 5, 6), handler.calls);
            assertEquals(6, identities.get()); assertEquals(4, extras.get());
        }
    }

    static class Parent {
        boolean called;
        @HandlerMethod public void run(R1 request) { called = true; }
    }
    @Handler(routeType = TestRoutes.Account.class) static class Inherited extends Parent {}
    @Handler(routeType = TestRoutes.Account.class) static class Masked extends Parent {
        @Override public void run(R1 request) { fail("Unannotated override is not an entry"); }
    }
    interface DefaultMethod {
        @HandlerMethod default void run(R1 request) { assertEquals(TestRoutes.Account.class, HandlerContexts.current().routeType()); }
    }
    @Handler(routeType = TestRoutes.Account.class) static class DefaultHandler implements DefaultMethod {}

    static GameRuntime.Builder single() {
        return builder().protocols(ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, R1.class).build())
                .defaultRoute(TestRoutes.Account.class, R1.class, request -> 1L);
    }

    @Test void inheritedEntryBindsToConcreteInstance() throws Exception {
        Inherited handler = new Inherited();
        try (GameRuntime runtime = single().handler(handler).build()) {
            done(runtime.command(new R1(), null)); assertTrue(handler.called);
        }
    }

    @Test void unannotatedOverrideMasksInheritedEntry() {
        try (GameRuntime runtime = single().handler(new Masked()).build()) {
            assertFalse(runtime.commands().contains(R1.class));
        }
    }

    @Test void annotatedDefaultInterfaceMethodIsDiscovered() throws Exception {
        try (GameRuntime runtime = single().handler(new DefaultHandler()).build()) { done(runtime.command(new R1(), null)); }
    }
}
