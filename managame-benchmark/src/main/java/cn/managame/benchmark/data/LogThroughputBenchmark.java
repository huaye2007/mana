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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Logs per second through one log type, queued by four business threads at once and written by {@code flushLogs()}
 * through a writer that drops what it is given: what the queue and a round of logs cost the framework itself, and how
 * much threads logging into one queue wait for each other. Each thread queues {@value #LOGS} logs and has them written,
 * so the queue holds a few thousand at most. Left to rounds on a timer, four threads queue more than one thread writes,
 * and the result measures the garbage collector moving a queue of millions.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LogThroughputBenchmark {
    static final int LOGS = 1000;

    public record ItemLog(long playerId, int configId, int delta) {}

    public static final class ItemLogs extends LogRepository<ItemLog> {}

    private final ItemLog log = new ItemLog(1001, 5001, 1);
    private GameData data;
    private ItemLogs logs;

    /** A long flush interval leaves writing to the benchmark's own {@code flushLogs()}. */
    @Setup(Level.Trial) public void start() {
        data = GameData.builder().errorHandler(failure -> {}).logRepositories((type, written) -> {}, List.of(ItemLogs.class))
                .flushInterval(Duration.ofHours(1)).build();
        logs = data.repository(ItemLogs.class);
    }

    @TearDown(Level.Trial) public void stop() { data.close(); }

    @Benchmark @OperationsPerInvocation(LOGS) public void insert() {
        for (int i = 0; i < LOGS; i++) logs.insert(log);
        data.flushLogs();
    }
}
