package cn.managame.benchmark.runtime;

import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.Dispatcher;
import cn.managame.runtime.dispatch.HandleContext;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
 * Player requests answered per second through the Dispatcher, as a gateway hands them over: four threads dispatch
 * decoded messages from the players of one domain to an annotated handler, whose answer comes back through the
 * reply callback, sharing a window of requests not yet answered. A request that fails fails the benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class DispatchThroughputBenchmark {
    static final int PLAYER = ExecuteThroughputBenchmark.PLAYER, PLAYERS = ExecuteThroughputBenchmark.PLAYERS, GUILD = 2;
    @Param({"virtualThreads", "striped"})
    public String executor;
    @Param({"4096"})
    public int window;
    private final GameContext[] players = new GameContext[PLAYERS];
    private final AtomicLong failures = new AtomicLong();
    private GameRuntime runtime;
    private Dispatcher dispatcher;
    private Semaphore inFlight;
    private Consumer<Object> onReply;
    private IntConsumer onFail;

    public record Buy(int item) {}
    public record Donate(long guildId) {}

    @Domain(PLAYER)
    public static class Shop {
        @HandleMethod public void buy(HandleContext context, Buy buy) { context.reply(buy.item()); }
    }

    @Domain(GUILD)
    public static class Guild {
        @HandleMethod(routeKey = "guildId") public void donate(HandleContext context, Donate donate) { context.reply(donate.guildId()); }
    }

    @Setup(Level.Trial) public void start() {
        inFlight = new Semaphore(window);
        onReply = result -> inFlight.release();
        onFail = code -> {
            failures.incrementAndGet();
            inFlight.release();
        };
        runtime = GameRuntime.builder()
                .domain(PLAYER, ExecuteThroughputBenchmark.executor(executor), RouteKeys.businessId())
                .domain(GUILD, ExecuteThroughputBenchmark.executor(executor))
                .build();
        dispatcher = Dispatcher.builder(runtime).register(List.of(new Shop(), new Guild())).build();
        for (int id = 0; id < PLAYERS; id++) players[id] = GameContext.of(PLAYER, id, ExecuteThroughputBenchmark.ROLE, id);
    }

    @TearDown(Level.Iteration) public void settle() throws InterruptedException {
        inFlight.acquire(window);
        inFlight.release(window);
        if (failures.get() > 0) throw new IllegalStateException(failures.get() + " requests failed");
    }

    @TearDown(Level.Trial) public void stop() { runtime.close(); }

    @Benchmark public void dispatch(ExecuteThroughputBenchmark.Player player) throws InterruptedException {
        inFlight.acquire();
        int id = player.next();
        dispatcher.dispatch(players[id], new Buy(id), onReply, onFail);
    }

    /** The same, in a domain whose handler routes by a member of its message, the guild it donates to. */
    @Benchmark public void dispatchByMember(ExecuteThroughputBenchmark.Player player) throws InterruptedException {
        inFlight.acquire();
        int id = player.next();
        dispatcher.dispatch(players[id], new Donate(id), onReply, onFail);
    }
}
