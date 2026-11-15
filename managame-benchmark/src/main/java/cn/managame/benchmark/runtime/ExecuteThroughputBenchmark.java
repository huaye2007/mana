package cn.managame.benchmark.runtime;

import cn.managame.metrics.Metrics;
import cn.managame.runtime.Context;
import cn.managame.runtime.Continuation;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.TaskInterceptor;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutors;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Tasks run per second through {@code GameRuntime.execute}, the single entry for business logic: four submitting
 * threads, or sixteen, spread tasks over the players of one domain, sharing a window of tasks not yet run, and each task frees
 * room. Tasks of one player run one at a time; the executor decides how players share threads. {@code executeIntercepted}
 * runs the same tasks through a runtime with one interceptor that does nothing, the cost of having interceptors.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ExecuteThroughputBenchmark {
    static final int PLAYER = 1, PLAYERS = 1024;
    static final byte ROLE = 1;
    @Param({"virtualThreads", "striped"})
    public String executor;
    @Param({"4096"})
    public int window;
    private final GameContext[] players = new GameContext[PLAYERS];
    private GameRuntime runtime, intercepted;
    /** Each player's way back, taken once in a task of the player's: what a task keeps while another server answers. */
    private final Continuation[] backs = new Continuation[PLAYERS];
    private Semaphore inFlight;
    private GameTask<GameContext> task;
    private GameTask<Context> continued;

    @Setup(Level.Trial) public void start() throws InterruptedException {
        inFlight = new Semaphore(window);
        task = context -> inFlight.release();
        continued = context -> inFlight.release();
        runtime = GameRuntime.builder().domain(PLAYER, executor(executor), RouteKeys.businessId()).metrics(metrics()).build();
        intercepted = GameRuntime.builder().domain(PLAYER, executor(executor), RouteKeys.businessId())
                .intercept(List.of(new TaskInterceptor() {})).build();
        CountDownLatch taken = new CountDownLatch(PLAYERS);
        for (int id = 0; id < PLAYERS; id++) {
            int player = id;
            players[id] = GameContext.of(PLAYER, id, ROLE, id);
            runtime.execute(players[id], context -> {
                backs[player] = runtime.continuation();
                taken.countDown();
            });
        }
        taken.await();
    }

    /** Where the runtime reports; here nowhere. */
    protected Metrics metrics() { return Metrics.noop(); }

    static RouteExecutor executor(String name) {
        return name.equals("striped") ? RouteExecutors.striped(Runtime.getRuntime().availableProcessors()) : RouteExecutors.virtualThreads();
    }

    @TearDown(Level.Iteration) public void settle() throws InterruptedException {
        inFlight.acquire(window);
        inFlight.release(window);
    }

    @TearDown(Level.Trial) public void stop() {
        runtime.close();
        intercepted.close();
    }

    @Benchmark public void execute(Player player) throws InterruptedException {
        inFlight.acquire();
        runtime.execute(players[player.next()], task);
    }

    @Benchmark public void executeIntercepted(Player player) throws InterruptedException {
        inFlight.acquire();
        intercepted.execute(players[player.next()], task);
    }

    /** A task that calls the next player's route and comes back: three tasks, the cost of {@code call}. */
    @Benchmark public void executeAndCall(Player player) throws InterruptedException {
        inFlight.acquire();
        int id = player.next();
        runtime.execute(players[id], context -> runtime.call(players[(id + 1) & (PLAYERS - 1)], () -> id, done -> inFlight.release()));
    }

    /**
     * What comes back to a player's route through its {@code Continuation}, from threads that are no game tasks, as the
     * answers of calls to another server do: the same tasks as {@code execute}, handed over past the limits on load.
     */
    @Benchmark public void continuation(Player player) throws InterruptedException {
        inFlight.acquire();
        backs[player.next()].execute(continued);
    }

    /** Sixteen submitting threads, as many business threads handing work on: how far the runtime's shared state scales. */
    @Benchmark @Threads(16) public void executeFromSixteenThreads(Player player) throws InterruptedException {
        inFlight.acquire();
        runtime.execute(players[player.next()], task);
    }

    /** The players one submitting thread acts for, in turn. */
    @State(Scope.Thread)
    public static class Player {
        private int next;

        int next() { return next++ & (PLAYERS - 1); }
    }
}
