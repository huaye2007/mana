package cn.managame.benchmark.runtime;

import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutors;
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
 * threads spread tasks over the players of one domain, sharing a window of tasks not yet run, and each task frees
 * room. Tasks of one player run one at a time; the executor decides how players share threads.
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
    private GameRuntime runtime;
    private Semaphore inFlight;
    private GameTask<GameContext> task;

    @Setup(Level.Trial) public void start() {
        inFlight = new Semaphore(window);
        task = context -> inFlight.release();
        runtime = GameRuntime.builder().domain(PLAYER, executor(executor), RouteKeys.businessId()).build();
        for (int id = 0; id < PLAYERS; id++) players[id] = GameContext.of(PLAYER, id, ROLE, id);
    }

    static RouteExecutor executor(String name) {
        return name.equals("striped") ? RouteExecutors.striped(Runtime.getRuntime().availableProcessors()) : RouteExecutors.virtualThreads();
    }

    @TearDown(Level.Iteration) public void settle() throws InterruptedException {
        inFlight.acquire(window);
        inFlight.release(window);
    }

    @TearDown(Level.Trial) public void stop() { runtime.close(); }

    @Benchmark public void execute(Player player) throws InterruptedException {
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
