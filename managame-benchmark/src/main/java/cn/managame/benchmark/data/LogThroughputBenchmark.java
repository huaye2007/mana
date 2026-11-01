package cn.managame.benchmark.data;

import cn.managame.data.GameData;
import cn.managame.data.LogRepository;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
 * Logs queued per second by four business threads at once into one log type, as item logs are on every change of a
 * bag: how much threads logging into one queue wait for each other. A writer that drops what it is given empties the
 * queue every 10 ms, so it never fills.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LogThroughputBenchmark {
    public record ItemLog(long playerId, int configId, int delta) {}

    public static final class ItemLogs extends LogRepository<ItemLog> {}

    private final ItemLog log = new ItemLog(1001, 5001, 1);
    private GameData data;
    private ItemLogs logs;

    @Setup(Level.Trial) public void start() {
        data = GameData.builder().errorHandler(failure -> {}).logRepositories((type, written) -> {}, List.of(ItemLogs.class))
                .flushInterval(Duration.ofMillis(10)).logQueueCapacity(1 << 24).build();
        logs = data.repository(ItemLogs.class);
    }

    @TearDown(Level.Trial) public void stop() { data.close(); }

    @Benchmark public boolean insert() { return logs.insert(log); }
}
