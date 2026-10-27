package cn.managame.benchmark.common;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
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

/**
 * Metadata as every message carries it: a trace id, the calling server and the acting role. Building it, reading a
 * value back, and wrapping received bytes, which validates them.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MetadataBenchmark {
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);
    private static final MetadataKey<Integer> SERVER = MetadataKey.intKey(2);
    private static final MetadataKey<Long> ROLE = MetadataKey.longKey(3);
    private final Metadata metadata = build("4bf92f3577b34da6a3ce929d0e0e4736", 101, 1001L);
    private final byte[] received = metadata.bytes().clone();
    private String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
    private int server = 101;
    private long role = 1001L;

    private static Metadata build(String trace, int server, long role) {
        return Metadata.builder().put(TRACE, trace).put(SERVER, server).put(ROLE, role).build();
    }

    @Benchmark public Metadata build() { return build(trace, server, role); }

    @Benchmark public Long get() { return metadata.get(ROLE); }

    @Benchmark public Metadata wrap() { return Metadata.wrap(received); }
}
