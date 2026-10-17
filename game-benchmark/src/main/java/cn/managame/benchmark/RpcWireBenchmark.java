package cn.managame.benchmark;

import cn.managame.rpc.message.RpcRequest;
import cn.managame.rpc.netty.RpcWire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Encoding and decoding one RPC request frame; bodies above 2 KiB are referenced instead of copied. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RpcWireBenchmark {
    @Param({"64", "1024", "8192"})
    int bodySize;

    private byte[] payload;

    @Setup public void setup() { payload = new byte[bodySize]; }

    @Benchmark public int encodeRequest() {
        ByteBuf body = PooledByteBufAllocator.DEFAULT.buffer(bodySize).writeBytes(payload);
        ByteBuf frame = RpcWire.encodeRequest(new RpcRequest(1001, 42L, 1, 10001L, null, body), 7, 4 * 1024 * 1024);
        try { return frame.readableBytes(); } finally { frame.release(); }
    }

    @Benchmark public int encodeAndDecodeRequest() {
        ByteBuf body = PooledByteBufAllocator.DEFAULT.buffer(bodySize).writeBytes(payload);
        ByteBuf frame = RpcWire.encodeRequest(new RpcRequest(1001, 42L, 1, 10001L, null, body), 7, 4 * 1024 * 1024);
        try {
            frame.skipBytes(RpcWire.TYPE_OFFSET + 1);
            return RpcWire.decodeRequest(frame).body().readableBytes();
        } finally { frame.release(); }
    }
}
