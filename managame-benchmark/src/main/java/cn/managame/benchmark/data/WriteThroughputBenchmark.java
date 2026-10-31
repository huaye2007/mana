package cn.managame.benchmark.data;

import cn.managame.benchmark.data.DataFixture.Player;
import cn.managame.benchmark.data.DataFixture.Players;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Changes written per second by {@code flush()}, through a mapper that reads every field and keeps nothing: what a
 * round costs the framework itself (taking the changes, grouping them by operation, batching, putting back), apart
 * from any database. {@value #ROWS} changed players per round.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class WriteThroughputBenchmark {
    static final int ROWS = 1000;
    private final Player[] entities = new Player[ROWS];
    private DataFixture fixture;
    private Players players;

    @Setup(Level.Trial) public void start() {
        fixture = new DataFixture();
        players = fixture.players();
        for (int i = 0; i < ROWS; i++) {
            entities[i] = new Player(i);
            players.insert(entities[i]);
        }
        fixture.data.flush();
    }

    @TearDown(Level.Trial) public void stop() { fixture.close(); }

    @Benchmark @OperationsPerInvocation(ROWS) public void update() {
        for (Player player : entities) {
            player.gold++;
            players.update(player);
        }
        fixture.data.flush();
    }
}
