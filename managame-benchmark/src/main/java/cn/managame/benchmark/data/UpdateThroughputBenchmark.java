package cn.managame.benchmark.data;

import cn.managame.benchmark.data.DataFixture.Player;
import cn.managame.benchmark.data.DataFixture.Players;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Changes recorded per second by four business threads at once, each changing players of its own as Routes do:
 * how much threads recording into one table wait for each other. No save runs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class UpdateThroughputBenchmark {
    static final int PLAYERS_PER_THREAD = 1024;
    private final AtomicInteger threads = new AtomicInteger();
    private DataFixture fixture;
    private Players players;

    @Setup(Level.Trial) public void start() {
        fixture = new DataFixture();
        players = fixture.players();
    }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    @Benchmark public void update(ThreadPlayers threadPlayers) {
        Player player = threadPlayers.next();
        player.gold++;
        players.update(player);
    }

    /** One business thread's own players. */
    @State(Scope.Thread)
    public static class ThreadPlayers {
        private final Player[] players = new Player[PLAYERS_PER_THREAD];
        private int next;

        @Setup(Level.Trial) public void start(UpdateThroughputBenchmark benchmark) {
            int first = benchmark.threads.getAndIncrement() * PLAYERS_PER_THREAD;
            for (int i = 0; i < PLAYERS_PER_THREAD; i++) {
                players[i] = new Player(first + i);
                benchmark.players.insert(players[i]);
            }
        }

        Player next() { return players[next++ & (PLAYERS_PER_THREAD - 1)]; }
    }
}
