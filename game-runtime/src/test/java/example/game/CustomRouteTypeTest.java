package example.game;

import cn.managame.runtime.annotation.Cron;
import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.clock.MutableGameClock;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.execution.ExecutionDomain;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.execution.HandlerContexts;
import cn.managame.runtime.execution.RouteTask;
import cn.managame.runtime.execution.RuntimeCallback;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteKeyResolver;
import cn.managame.runtime.route.RouteType;

import cn.managame.runtime.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Timeout;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
public class CustomRouteTypeTest {
    public interface Player extends RouteType {}
    public static final class OtherGame { public interface Player extends RouteType {} }
    public record Request(long key) {}
    public record OtherRequest(long key) {}
    public record Changed(long key) {}

    static void done(RouteTask task) throws Exception { task.get(5, TimeUnit.SECONDS); }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test void exactTypeIdentitySeparatesSameNamedTypesWhileSameRouteQueues() throws Exception {
        assertEquals(Player.class.getSimpleName(), OtherGame.Player.class.getSimpleName());
        try (GameRuntime runtime = GameRuntime.builder().automaticScheduling(false).build()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            RouteTask first = runtime.dispatch(Player.class, 7, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                RouteTask second = runtime.dispatch(Player.class, 7, () ->
                        assertEquals(new Route(Player.class, 7), HandlerContexts.current().route()));
                done(runtime.dispatch(OtherGame.Player.class, 7, () ->
                        assertSame(OtherGame.Player.class, HandlerContexts.current().routeType())));
                done(runtime.dispatch(Player.class, 8, () -> {}));
                assertFalse(second.isDone());
                release.countDown();
                done(first);
                done(second);
            } finally { release.countDown(); }
        }
    }

    @Handler(routeType = Player.class)
    public static class PlayerHandler {
        GameRuntime runtime;
        RuntimeCallback<String> callback;
        boolean fixedThread;
        Thread worker;
        final List<String> calls = new CopyOnWriteArrayList<>();
        void record(String call) {
            if (fixedThread) {
                if (worker == null) worker = Thread.currentThread();
                else assertSame(worker, Thread.currentThread());
            }
            assertEquals(new Route(Player.class, 7), HandlerContexts.current().route());
            assertFalse(Thread.currentThread().isVirtual());
            assertTrue(Thread.currentThread().getName().startsWith("game-player-"));
            calls.add(call);
        }
        @HandlerMethod public void run(Request request) {
            record("command");
            runtime.publish(new Changed(request.key()));
            callback = runtime.callback(this::record);
        }
    }

    @EventHandler(routeType = Player.class, routeKeyResolver = EventKey.class)
    public static class Events {
        final PlayerHandler handler;
        Events(PlayerHandler handler) { this.handler = handler; }
        @EventMethod public void changed(Changed event) { handler.record("event"); }
    }
    public static class EventKey implements RouteKeyResolver<Changed> {
        public long resolve(Changed event) { return event.key(); }
    }
    public static class Crons {
        final PlayerHandler handler;
        Crons(PlayerHandler handler) { this.handler = handler; }
        @Cron(value = "* * * * * ?", routeType = Player.class, routeKey = 7)
        public void tick() { handler.record("cron"); }
    }

    @ParameterizedTest @EnumSource(value = ExecutionDomain.Scheduling.class, names = {"BALANCED", "KEY_AFFINITY"})
    void commandEventCronTimerAndCallbackShareTheApplicationRoute(ExecutionDomain.Scheduling scheduling) throws Exception {
        PlayerHandler handler = new PlayerHandler();
        handler.fixedThread = scheduling == ExecutionDomain.Scheduling.KEY_AFFINITY;
        var clock = new MutableGameClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        List<HandlerException> errors = new CopyOnWriteArrayList<>();
        try (GameRuntime runtime = GameRuntime.builder().clock(clock).automaticScheduling(false)
                .executionDomain(Player.class, ExecutionDomain.platform("player").threads(2).scheduling(scheduling).tasksPerTurn(1).build()).exceptionHandler(errors::add)
                .protocols(ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, Request.class).build())
                .defaultRoute(Player.class, Request.class, Request::key)
                .eventType(Changed.class).routeKeyResolver(Changed.class, new EventKey())
                .handler(handler).handler(new Events(handler)).handler(new Crons(handler)).build()) {
            handler.runtime = runtime;
            done(runtime.command(new Request(7), null));
            assertEquals(List.of("command", "event"), handler.calls);
            assertEquals(new Route(Player.class, 7), handler.callback.route());

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            RouteTask blocker = runtime.dispatch(Player.class, 7, () -> { entered.countDown(); await(release); });
            try {
                await(entered);
                assertTrue(handler.callback.onSuccess("callback"));
                runtime.publish(new Changed(7));
                runtime.schedule(Player.class, 7, Duration.ZERO, () -> handler.record("timer"));
                clock.advance(Duration.ofSeconds(1));
                runtime.runDueTimers();
                assertEquals(List.of("command", "event"), handler.calls);
                release.countDown();
                done(blocker);
                done(runtime.dispatch(Player.class, 7, () -> {}));
                assertEquals(List.of("command", "event", "callback", "event", "timer", "cron"), handler.calls);
                assertTrue(errors.isEmpty(), errors::toString);
            } finally { release.countDown(); }
        }
    }

    @Handler(routeType = OtherGame.Player.class)
    public static class OtherHandler {
        Route observed;
        @HandlerMethod public void run(OtherRequest request) { observed = HandlerContexts.current().route(); }
    }

    @Test void defaultResolversAreScopedByExactApplicationType() throws Exception {
        // This handler records its route without requiring the event/callback fixture.
        OtherHandler other = new OtherHandler();
        try (GameRuntime runtime = GameRuntime.builder().automaticScheduling(false)
                .protocols(ProtocolRegistry.builder().register(2, ProtocolType.REQUEST, OtherRequest.class).build())
                .defaultRoute(Player.class, OtherRequest.class, request -> 99L)
                .defaultRoute(OtherGame.Player.class, OtherRequest.class, OtherRequest::key)
                .handler(other).build()) {
            done(runtime.command(new OtherRequest(7), null));
            assertEquals(new Route(OtherGame.Player.class, 7), other.observed);
        }
    }
}
