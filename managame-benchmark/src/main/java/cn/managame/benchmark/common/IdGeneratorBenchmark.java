package cn.managame.benchmark.common;

import cn.managame.IdLayout;
import cn.managame.SequenceIdGenerator;
import cn.managame.SnowflakeIdGenerator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Ids from a snowflake generator, by one thread and by four sharing it, as business threads making items and mails
 * do, and from the in-process sequence. The default layout allows 4096 ids a millisecond, about 245 ns an id, so
 * {@code next} measures that limit; the others use a layout with sequence room enough that the clock never holds them
 * up.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class IdGeneratorBenchmark {
    private final SnowflakeIdGenerator ids = new SnowflakeIdGenerator(IdLayout.DEFAULT, 101);
    private final SnowflakeIdGenerator wide = new SnowflakeIdGenerator(new IdLayout(IdLayout.DEFAULT.epoch(), IdLayout.DEFAULT.tick(), 41, 22, 0), 0);
    private final SequenceIdGenerator sequence = new SequenceIdGenerator();

    /** One thread: the default layout's 4096 ids a millisecond bound it. */
    @Benchmark public long next() { return ids.generateId(); }

    /** One thread, with sequence room enough that the clock never holds it up: the generator's own cost. */
    @Benchmark public long nextUnbounded() { return wide.generateId(); }

    /** Four threads sharing a generator with room enough: the cost of contention on it. */
    @Benchmark @Threads(4) public long nextShared() { return wide.generateId(); }

    /** The in-process sequence, for comparison: one atomic increment. */
    @Benchmark public long sequence() { return sequence.generateId(); }
}
