package cn.managame.benchmark;

import cn.managame.core.Metadata;
import cn.managame.core.MetadataKey;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Reading typed values from encoded Metadata (linear key scan plus codec decode). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MetadataBenchmark {
    private static final MetadataKey<Long> TRACE = MetadataKey.longKey(1);
    private static final MetadataKey<Integer> SERVER = MetadataKey.intKey(2);
    private static final MetadataKey<String> LOCALE = MetadataKey.stringKey(3);
    private static final MetadataKey<Boolean> GM = MetadataKey.booleanKey(4);
    private final Metadata metadata = Metadata.builder()
            .put(TRACE, 123456789L).put(SERVER, 1001).put(LOCALE, "zh-CN").put(GM, false).build();

    @Benchmark public long firstKey() { return metadata.get(TRACE); }

    @Benchmark public boolean lastKey() { return metadata.get(GM); }

    @Benchmark public Metadata build() {
        return Metadata.builder().put(TRACE, 1L).put(SERVER, 2).build();
    }
}
