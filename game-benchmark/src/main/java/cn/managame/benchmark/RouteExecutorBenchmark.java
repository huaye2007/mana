package cn.managame.benchmark;

import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.StripedRouteExecutor;
import cn.managame.runtime.executor.VirtualThreadRouteExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Submitting a burst of small tasks spread over {@code routes} Routes and waiting until all ran: admission,
 * mailbox handoff and consumer wake-up cost per task.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class RouteExecutorBenchmark {
    private static final int TASKS = 10_000;

    @Param({"virtual", "striped"})
    String executor;

    @Param({"1", "100", "10000"})
    int routes;

    private RouteExecutor route;

    @Setup(Level.Trial) public void setup() {
        route = executor.equals("virtual")
                ? new VirtualThreadRouteExecutor(TASKS * 2, TASKS)
                : new StripedRouteExecutor(Runtime.getRuntime().availableProcessors(), TASKS * 2);
    }

    @TearDown(Level.Trial) public void tearDown() { route.close(); }

    @Benchmark
    @OperationsPerInvocation(TASKS)
    public void burst() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(TASKS);
        Runnable task = done::countDown;
        for (int i = 0; i < TASKS; i++) {
            while (route.tryExecute(1, 1 + (i % routes), task) != RouteExecuteStatus.ACCEPTED) Thread.onSpinWait();
        }
        if (!done.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("tasks did not finish");
    }
}
