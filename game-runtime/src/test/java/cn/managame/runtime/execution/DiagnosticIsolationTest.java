package cn.managame.runtime.execution;

import cn.managame.runtime.diagnostics.SlowTask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DiagnosticIsolationTest {
    private static void done(RouteTask task) throws Exception { task.get(5, TimeUnit.SECONDS); }
    private static GameRuntime runtime(int capacity) {
        return GameRuntime.builder().slowTaskDiagnostics(Duration.ZERO, capacity)
                .executionDomain(TestRoutes.Player.class, ExecutionDomain.platform("players").threads(2).build())
                .executionDomain(TestRoutes.Battle.class, ExecutionDomain.platform("battle").threads(2).build()).build();
    }

    @Test void busyDomainCannotEvictOtherDomainsSamples() throws Exception {
        try (var runtime = runtime(2)) {
            done(runtime.dispatch(TestRoutes.Battle.class, 1, () -> {}));
            done(runtime.dispatch(TestRoutes.Battle.class, 2, () -> {}));
            for (int i = 0; i < 10; i++) done(runtime.dispatch(TestRoutes.Player.class, i, () -> {}));
            assertEquals(List.of(1L, 2L), runtime.recentSlowTasks(TestRoutes.Battle.class)
                    .stream().map(sample -> sample.route().key()).toList());
            assertEquals(List.of(8L, 9L), runtime.recentSlowTasks(TestRoutes.Player.class)
                    .stream().map(sample -> sample.route().key()).toList());
            assertEquals(2, runtime.recentSlowTasks().size());
            assertTrue(runtime.recentSlowTasks().stream().allMatch(sample -> sample.domain().equals("players")));
        }
    }

    @Test void typesSharingADomainShareItsDiagnosticBudget() throws Exception {
        ExecutionDomain shared = ExecutionDomain.platform("shared").threads(2).build();
        try (var runtime = GameRuntime.builder().slowTaskDiagnostics(Duration.ZERO, 2)
                .executionDomain(TestRoutes.Player.class, shared).executionDomain(TestRoutes.Guild.class, shared).build()) {
            done(runtime.dispatch(TestRoutes.Player.class, 1, () -> {}));
            done(runtime.dispatch(TestRoutes.Guild.class, 2, () -> {}));
            done(runtime.dispatch(TestRoutes.Player.class, 3, () -> {}));
            assertEquals(runtime.recentSlowTasks(TestRoutes.Player.class), runtime.recentSlowTasks(TestRoutes.Guild.class));
            assertEquals(List.of(2L, 3L), runtime.recentSlowTasks().stream().map(sample -> sample.route().key()).toList());
        }
    }

    @Test void concurrentQueriesAndWritersKeepBoundedDomainSnapshots() throws Exception {
        AtomicBoolean finished = new AtomicBoolean();
        try (var runtime = runtime(16); var callers = Executors.newFixedThreadPool(5)) {
            Future<?> reader = callers.submit(() -> {
                while (!finished.get()) {
                    for (var type : List.of(TestRoutes.Player.class, TestRoutes.Battle.class)) {
                        List<SlowTask> snapshot = runtime.recentSlowTasks(type);
                        assertTrue(snapshot.size() <= 16);
                        assertTrue(snapshot.stream().allMatch(sample -> sample.route().type() == type));
                    }
                    assertTrue(runtime.routeDiagnostics(3).size() <= 3);
                }
            });
            try {
                List<Future<?>> writers = new ArrayList<>();
                for (int writer = 0; writer < 4; writer++) {
                    int id = writer;
                    writers.add(callers.submit(() -> {
                        var type = id % 2 == 0 ? TestRoutes.Player.class : TestRoutes.Battle.class;
                        for (int i = 0; i < 300; i++) done(runtime.dispatch(type, id, () -> {}));
                        return null;
                    }));
                }
                for (Future<?> writer : writers) writer.get(10, TimeUnit.SECONDS);
            } finally { finished.set(true); }
            reader.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 16; i++) done(runtime.dispatch(TestRoutes.Battle.class, 100 + i, () -> {}));
            assertEquals(16, runtime.recentSlowTasks(TestRoutes.Battle.class).size());
            assertEquals(115, runtime.recentSlowTasks(TestRoutes.Battle.class).getLast().route().key());
        }
    }
}
