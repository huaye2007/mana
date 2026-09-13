package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static cn.managame.runtime.execution.HandlerBindingTest.single;
import static cn.managame.runtime.execution.RouteRuntimeTest.done;
import static org.junit.jupiter.api.Assertions.*;

class GenericHandlerBindingTest {
    static class Parent<T> {
        @HandlerMethod public void run(T request) { fail("Overridden parent must not be registered"); }
    }

    @Handler(routeType = TestRoutes.Account.class)
    static class Annotated extends Parent<HandlerBindingTest.R1> {
        boolean called;
        @Override @HandlerMethod public void run(HandlerBindingTest.R1 request) { called = true; }
    }

    @Handler(routeType = TestRoutes.Account.class)
    static class Masked extends Parent<HandlerBindingTest.R1> {
        @Override public void run(HandlerBindingTest.R1 request) { fail("Unannotated override is not an entry"); }
    }

    interface GenericDefault<T> {
        @HandlerMethod default void run(T request) { fail("Overridden default must not be registered"); }
    }

    @Handler(routeType = TestRoutes.Account.class)
    static class AnnotatedDefault implements GenericDefault<HandlerBindingTest.R1> {
        boolean called;
        @Override @HandlerMethod public void run(HandlerBindingTest.R1 request) { called = true; }
    }

    @Handler(routeType = TestRoutes.Account.class)
    static class MaskedDefault implements GenericDefault<HandlerBindingTest.R1> {
        @Override public void run(HandlerBindingTest.R1 request) { fail("Unannotated override is not an entry"); }
    }

    @Test void annotatedGenericOverrideOnlyRegistersConcreteRequest() throws Exception {
        Annotated handler = new Annotated();
        try (GameRuntime runtime = single().handler(handler).build()) {
            assertTrue(runtime.commands().contains(HandlerBindingTest.R1.class));
            assertFalse(runtime.commands().contains(Object.class));
            done(runtime.command(new HandlerBindingTest.R1(), null));
            assertTrue(handler.called);
        }
    }

    @Test void unannotatedGenericOverrideMasksErasedParentMethod() {
        try (GameRuntime runtime = single().handler(new Masked()).build()) {
            assertFalse(runtime.commands().contains(HandlerBindingTest.R1.class));
            assertFalse(runtime.commands().contains(Object.class));
        }
    }

    @Test void annotatedGenericOverrideMasksErasedInterfaceDefault() throws Exception {
        AnnotatedDefault handler = new AnnotatedDefault();
        try (GameRuntime runtime = single().handler(handler).build()) {
            assertFalse(runtime.commands().contains(Object.class));
            done(runtime.command(new HandlerBindingTest.R1(), null));
            assertTrue(handler.called);
        }
    }

    @Test void unannotatedGenericOverrideMasksErasedInterfaceDefault() {
        try (GameRuntime runtime = single().handler(new MaskedDefault()).build()) {
            assertFalse(runtime.commands().contains(HandlerBindingTest.R1.class));
            assertFalse(runtime.commands().contains(Object.class));
        }
    }

    static class ReturningParent<T> {
        @HandlerMethod public T run(HandlerBindingTest.R1 request) { return null; }
    }

    @Handler(routeType = TestRoutes.Account.class)
    static class Returning extends ReturningParent<String> {
        @Override @HandlerMethod public String run(HandlerBindingTest.R1 request) { return ""; }
    }

    @Test void covariantBridgeCannotHideInvalidAnnotatedConcreteMethod() {
        List<Method> entries = HandlerMethodScanner.scan(Returning.class);
        assertEquals(1, entries.size());
        assertEquals(String.class, entries.getFirst().getReturnType());
        assertFalse(entries.getFirst().isBridge());
        assertThrows(IllegalArgumentException.class, () -> single().handler(new Returning()).build());
    }
}
