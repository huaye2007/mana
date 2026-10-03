package cn.managame.runtime;

import cn.managame.core.*;
import cn.managame.runtime.context.*;
import cn.managame.runtime.error.*;
import cn.managame.runtime.executor.*;
import cn.managame.runtime.handler.*;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.core.FrameworkErrorCodes.*;

class RuntimeDrainTest {
    record Request(Runnable action) {}
    @Handler(domain = 1) static class HandlerOwner {
        @HandlerMethod public void handle(Request request) { request.action().run(); }
    }
    static class QueueExecutor implements RouteExecutor {
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        RouteExecuteStatus status = RouteExecuteStatus.ACCEPTED;
        public RouteExecuteStatus tryExecute(int domain, long key, Runnable task) {
            if (status == RouteExecuteStatus.ACCEPTED) tasks.add(task);
            return status;
        }
        void next() { Objects.requireNonNull(tasks.poll()).run(); }
    }
    GameRuntime runtime(RouteExecutor executor, List<RuntimeError> errors) {
        return GameRuntimeBuilder.builder().routeDomains(List.of(RouteDomain.of(1, "role")))
                .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)))
                .protocols(List.of(r -> r.register(Protocols.request(1, Request.class))))
                .handlers(List.of(new HandlerOwner())).errorHandler(errors::add).build();
    }

    @Test void stopsExternalAdmissionButDrainsQueuedWorkAndItsNestedDispatch() throws Exception {
        var queue = new QueueExecutor(); var observed = new ArrayList<Long>();
        try (var runtime = runtime(queue, new ArrayList<>())) {
            runtime.dispatch(null, 7L, new Request(() -> {
                assertThrows(IllegalStateException.class, () -> runtime.awaitTermination(Duration.ZERO));
                observed.add(Contexts.current().routeKey());
                runtime.dispatch(null, 8L, new Request(() -> observed.add(Contexts.current().routeKey())));
                runtime.dispatch(null, 7L, new Request(() -> observed.add(Contexts.current().routeKey())));
            }));
            runtime.shutdown(); runtime.shutdown();
            assertFalse(runtime.awaitTermination(Duration.ZERO));
            assertEquals(RUNTIME_CLOSED, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(null, 9L, new Request(() -> fail("rejected")))).errorCode());
            queue.next(); assertFalse(runtime.awaitTermination(Duration.ZERO)); queue.next();
            assertTrue(runtime.awaitTermination(Duration.ofSeconds(1)));
            assertEquals(List.of(7L, 7L, 8L), observed);
            assertEquals(0, runtime.stats().inFlight()); assertEquals(3, runtime.stats().completed());
            assertEquals(0, runtime.stats().queued()); assertEquals(0, runtime.stats().running());
        }
    }

    @Test void asynchronousCallbackReservesDrainAndRestoresOriginalMetadataAndIdentity() throws Exception {
        var queue = new QueueExecutor(); var bound = new AtomicReference<RouteCallback<Integer>>();
        var delivered = new AtomicInteger(); var metadata = Metadatas.builder().put(MetadataKeys.intKey(1), 42).build();
        var errors = new ArrayList<RuntimeError>();
        try (var runtime = runtime(queue, errors)) {
            runtime.dispatch(null, 7L, 1, 100L, metadata, new Request(() -> bound.set(runtime.callback(new RouteCallback<>() {
                public void onSuccess(Integer value) {
                    var context = Contexts.current(ClientHandlerContext.class);
                    assertEquals(7, context.routeKey()); assertEquals(100, context.businessId());
                    assertSame(metadata, context.metadata()); delivered.addAndGet(value);
                }
                public void onFail(int error) { fail("Unexpected error " + error); }
            }))));
            queue.next(); runtime.shutdown();
            assertEquals(1, runtime.stats().inFlight()); assertFalse(runtime.awaitTermination(Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> bound.get().onFail(0));
            bound.get().onSuccess(3); bound.get().onSuccess(99);
            assertFalse(runtime.awaitTermination(Duration.ZERO)); queue.next();
            assertTrue(runtime.awaitTermination(Duration.ofSeconds(1))); assertEquals(3, delivered.get());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void rejectedTasksAndHandlerFailuresDoNotLeakDrainReservations() throws Exception {
        var queue = new QueueExecutor(); var errors = new ArrayList<RuntimeError>();
        try (var runtime = runtime(queue, errors)) {
            queue.status = RouteExecuteStatus.OVERLOADED;
            assertEquals(ROUTE_EXECUTOR_OVERLOADED, assertThrows(RuntimeDispatchException.class,
                    () -> runtime.dispatch(null, 7L, new Request(() -> fail("rejected")))).errorCode());
            queue.status = RouteExecuteStatus.ACCEPTED;
            runtime.dispatch(null, 7L, new Request(() -> { throw new IllegalStateException("business"); }));
            runtime.shutdown(); queue.next(); assertTrue(runtime.awaitTermination(Duration.ofSeconds(1)));
            assertEquals(1, errors.size()); assertEquals(1, runtime.stats().rejected());
            assertEquals(1, runtime.stats().errors()); assertEquals(0, runtime.stats().inFlight());
        }
    }

    @Test void concurrentShutdownCannotMissAcceptedTasks() throws Exception {
        var queue = new QueueExecutor();
        try (var runtime = runtime(queue, new ArrayList<>()); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1); var accepted = new AtomicInteger(); var executed = new AtomicInteger();
            runtime.dispatch(null, 7L, new Request(executed::incrementAndGet)); accepted.incrementAndGet();
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) futures.add(threads.submit(() -> {
                try { start.await(); runtime.dispatch(null, 7L, new Request(executed::incrementAndGet)); accepted.incrementAndGet(); }
                catch (RuntimeDispatchException rejection) { assertEquals(RUNTIME_CLOSED, rejection.errorCode()); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }));
            start.countDown(); runtime.shutdown();
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
            while (!queue.tasks.isEmpty()) queue.next();
            assertTrue(runtime.awaitTermination(Duration.ofSeconds(1)));
            assertEquals(accepted.get(), executed.get()); assertEquals(0, runtime.stats().inFlight());
        }
    }

    @Test void rejectedCallbackReturnReportsErrorAndReleasesDrainReservation() throws Exception {
        var queue = new QueueExecutor(); var bound = new AtomicReference<RouteCallback<Integer>>();
        var errors = new ArrayList<RuntimeError>();
        try (var runtime = runtime(queue, errors)) {
            runtime.dispatch(null, 7L, new Request(() -> bound.set(runtime.callback(new RouteCallback<>() {
                public void onSuccess(Integer value) { fail("Rejected return ran"); }
                public void onFail(int error) { fail("Rejected return ran"); }
            }))));
            queue.next(); runtime.shutdown(); queue.status = RouteExecuteStatus.CLOSED;
            bound.get().onFail(100); assertTrue(runtime.awaitTermination(Duration.ofSeconds(1)));
            assertEquals(1, errors.size()); assertEquals(ROUTE_CALLBACK_DISPATCH_FAILED, errors.getFirst().errorCode());
            assertEquals(0, runtime.stats().inFlight());
        }
    }
}
