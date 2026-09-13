package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.RuntimeOverloadedException;
import cn.managame.runtime.route.Route;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(15)
class ConfigurationBoundariesTest {
    @Test void timerOptionsDoNotChangeExplicitDomainAdmission() throws Exception {
        var domain = ExecutionDomain.platform("configured").threads(1)
                .limits(new DomainLimits(2, 2, 1, 1)).routeShards(3).build();
        try (GameRuntime runtime = builder().executionDomain(TestRoutes.Player.class, domain)
                .timerOptions(new TimerOptions(2, 1)).build()) {
            verifyAdmissionAndTimers(runtime);
        }
    }

    @Test void splitOptionsConfigureTheDefaultDomain() throws Exception {
        try (GameRuntime runtime = builder().domainLimits(new DomainLimits(2, 2, 1, 1))
                .routeShards(3).timerOptions(new TimerOptions(2, 1)).build()) {
            verifyAdmissionAndTimers(runtime);
        }
    }

    @SuppressWarnings("removal")
    @Test void legacyBridgeStillAppliesTaskReserveAndTimerFields() throws Exception {
        try (GameRuntime runtime = builder().limits(new RuntimeLimits(2, 2, 1, 1, 2, 3, 1)).build()) {
            verifyAdmissionAndTimers(runtime);
        }
    }

    private static void verifyAdmissionAndTimers(GameRuntime runtime) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        RouteTask first = runtime.dispatch(TestRoutes.Player.class, 7, () -> {
            entered.countDown(); await(release); calls.incrementAndGet();
        });
        try {
            await(entered);
            RouteTask second = runtime.dispatch(TestRoutes.Player.class, 7, calls::incrementAndGet);
            assertThrows(RuntimeOverloadedException.class,
                    () -> runtime.dispatch(TestRoutes.Player.class, 7, () -> fail("Over capacity")));
            var callback = runtime.callback(new CallbackDefinition<String>(new Route(TestRoutes.Player.class, 7),
                    ignored -> calls.incrementAndGet(), null));
            assertTrue(callback.onSuccess("reserved"));
            assertEquals(3, runtime.metrics().outstandingTasks());

            CountDownLatch timerOne = new CountDownLatch(1), timerTwo = new CountDownLatch(1);
            runtime.schedule(TestRoutes.Player.class, 7, Duration.ZERO, timerOne::countDown);
            runtime.schedule(TestRoutes.Player.class, 7, Duration.ZERO, timerTwo::countDown);
            var rejection = assertThrows(RuntimeOverloadedException.class,
                    () -> runtime.schedule(TestRoutes.Player.class, 7, Duration.ZERO, () -> fail("Timer capacity")));
            assertEquals(RuntimeOverloadedException.Reason.TIMER_CAPACITY, rejection.reason());

            release.countDown();
            done(first); done(second); done(callback.completion());
            assertEquals(3, calls.get());
            assertEquals(1, runtime.runDueTimers());
            await(timerOne);
            assertEquals(1, runtime.metrics().scheduledTimers());
            assertEquals(1, runtime.runDueTimers());
            await(timerTwo);
            assertEquals(0, runtime.metrics().scheduledTimers());
        } finally { release.countDown(); }
    }
}
