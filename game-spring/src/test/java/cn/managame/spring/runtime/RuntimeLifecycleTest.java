package cn.managame.spring.runtime;

import cn.managame.runtime.*;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.*;
import cn.managame.runtime.route.*;
import cn.managame.runtime.error.RuntimeDispatchException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.context.event.ContextClosedEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.core.FrameworkErrorCodes.RUNTIME_CLOSED;

class RuntimeLifecycleTest {
    record Request(Runnable action) {}
    @Handler(domain = 1) @Profile("manual-lifecycle-only") static class Owner {
        @HandlerMethod public void request(Request request) { request.action().run(); }
    }
    static class Resource implements AutoCloseable {
        final AtomicBoolean closed = new AtomicBoolean();
        public void close() { closed.set(true); }
    }
    @Configuration @EnableGameRuntime(basePackages = "cn.managame.spring.runtime.empty")
    static class Config {
        @Bean Owner owner() { return new Owner(); }
        @Bean ProtocolRegistry protocols() { return ProtocolRegistry.of(ProtocolDescriptor.request(1, Request.class)); }
        @Bean ProtocolRegistry moreProtocols() { return ProtocolRegistry.of(ProtocolDescriptor.notify(2, Resource.class)); }
        @Bean(destroyMethod = "close") Resource resource() { return new Resource(); }
        @Bean(destroyMethod = "close") RouteExecutor routeExecutor() { return RouteExecutors.virtualThreads(); }
        @Bean GameRuntimeConfigurer configurer(RouteExecutor executor) {
            return builder -> builder.routeDomains(List.of(RouteDomain.of(1, "role")))
                    .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)));
        }
    }
    @Test void contextCloseWaitsForWorkBeforeOrdinaryListenersAndResourceDestruction() throws Exception {
        var spring = new AnnotationConfigApplicationContext(Config.class);
        var runtime = spring.getBean(GameRuntime.class); var resource = spring.getBean(Resource.class);
        assertEquals(Request.class, runtime.protocols().get(ProtocolDescriptor.Type.REQUEST, 1).messageType());
        assertEquals(Resource.class, runtime.protocols().get(ProtocolDescriptor.Type.NOTIFY, 2).messageType()); // registries merged
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new AtomicBoolean();
        var listenerObserved = new AtomicBoolean();
        spring.addApplicationListener((ContextClosedEvent event) -> {
            assertTrue(done.get()); assertFalse(resource.closed.get()); listenerObserved.set(true);
        });
        try (var thread = Executors.newVirtualThreadPerTaskExecutor()) {
            runtime.dispatch(null, 9L, new Request(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                assertFalse(resource.closed.get()); assertEquals(9, Contexts.current().routeKey());
                done.set(true);
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS)); var close = thread.submit(spring::close);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (runtime.stats().accepting() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(runtime.stats().accepting());
            assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(null, 9L, new Request(() -> fail("rejected")))).errorCode());
            assertFalse(close.isDone()); assertFalse(resource.closed.get());
            release.countDown(); close.get(3, TimeUnit.SECONDS);
            assertTrue(listenerObserved.get()); assertTrue(resource.closed.get());
        } finally { release.countDown(); spring.close(); }
        try (var restarted = new AnnotationConfigApplicationContext(Config.class)) {
            assertTrue(restarted.getBean(GameRuntime.class).stats().accepting());
        }
    }
    @Test void aCallbackThatNeverCompletesCannotBlockShutdownBeyondTheDrainTimeout() throws Exception {
        var spring = new AnnotationConfigApplicationContext();
        spring.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "drain", Map.of("game.runtime.drain-timeout-millis", "200")));
        spring.register(Config.class); spring.refresh();
        var runtime = spring.getBean(GameRuntime.class); var captured = new CountDownLatch(1);
        runtime.dispatch(null, 9L, new Request(() -> {
            runtime.callback(new RouteCallback<Object>() { // never completed
                public void onSuccess(Object result) {} public void onFail(int error) {}
            });
            captured.countDown();
        }));
        assertTrue(captured.await(3, TimeUnit.SECONDS));
        long started = System.nanoTime();
        spring.close();
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "shutdown must not wait forever");
    }
}
