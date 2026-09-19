package cn.managame.rpc.netty;

import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import cn.managame.network.*;
import cn.managame.rpc.core.*;

import io.netty.buffer.*;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Standalone bounded TCP round-trip baseline; deliberately excluded from unit-test execution. */
public final class RpcLoadBenchmark {
    record Metrics(long elapsed, long allocated, long gcCount, long gcMillis, long cpuNanos) {
        static Metrics read() {
            var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long allocated =
                    threads.isThreadAllocatedMemorySupported()
                            ? threads.getTotalThreadAllocatedBytes()
                            : -1;
            long count = 0, millis = 0;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            var os =
                    (com.sun.management.OperatingSystemMXBean)
                            ManagementFactory.getOperatingSystemMXBean();
            return new Metrics(System.nanoTime(), allocated, count, millis, os.getProcessCpuTime());
        }
    }

    static long[] run(RpcNode client, ByteBuf body, int requests, int window) throws Exception {
        var latency = new long[requests];
        var permits = new Semaphore(window);
        var done = new CountDownLatch(requests);
        var errors = new AtomicInteger();
        for (int i = 0; i < requests; i++) {
            if (!permits.tryAcquire(15, TimeUnit.SECONDS))
                throw new IllegalStateException("No call completion");
            int index = i;
            long start = System.nanoTime();
            client.call(
                    20,
                    1,
                    body,
                    RpcOptions.DEFAULT,
                    result -> {
                        latency[index] = System.nanoTime() - start;
                        if (!result.isSuccess()
                                || result.value().body().readableBytes() != body.readableBytes())
                            errors.incrementAndGet();
                        permits.release();
                        done.countDown();
                    });
        }
        if (!done.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Incomplete calls");
        if (errors.get() != 0) throw new IllegalStateException("Failed calls: " + errors.get());
        return latency;
    }

    static double percentile(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p) - 1)] / 1000.0;
    }

    public static void main(String[] args) throws Exception {
        int requests = Integer.parseInt(args[0]), warmup = Integer.parseInt(args[1]);
        int bytes = Integer.parseInt(args[2]),
                slots = Integer.parseInt(args[3]),
                window = Integer.parseInt(args[4]);
        boolean flush = Boolean.parseBoolean(args[5]), diagnostics = Boolean.parseBoolean(args[6]);
        if (requests <= 0 || warmup <= 0 || bytes <= 0 || slots <= 0 || window <= 0)
            throw new IllegalArgumentException("Positive workload sizes required");
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (threads.isThreadAllocatedMemorySupported() && !threads.isThreadAllocatedMemoryEnabled())
            threads.setThreadAllocatedMemoryEnabled(true);
        var serverRef = new AtomicReference<RpcNode>();
        var clientBuilder =
                RpcNode.builder()
                        .nodeId(10)
                        .listen("127.0.0.1", 0)
                        .defaultTimeout(Duration.ofSeconds(10))
                        .consolidateFlush(flush)
                        .handler((c, m) -> {});
        var serverBuilder =
                RpcNode.builder()
                        .nodeId(20)
                        .listen("127.0.0.1", 0)
                        .defaultTimeout(Duration.ofSeconds(10))
                        .consolidateFlush(flush)
                        .handler(
                                (c, m) -> {
                                    var q = (RpcRequest) m;
                                    serverRef
                                            .get()
                                            .reply(
                                                    c,
                                                    new RpcResponse(
                                                            q.requestId(),
                                                            0,
                                                            RpcMetadata.EMPTY,
                                                            q.body()));
                                });
        // Count diagnostic delivery; no logging or external IO in the measurement.
        var diagnosticCount = new LongAdder();
        if (diagnostics) {
            clientBuilder.diagnostics(d -> diagnosticCount.increment());
            serverBuilder.diagnostics(d -> diagnosticCount.increment());
        }
        ByteBuf body = Unpooled.buffer(bytes).writeZero(bytes);
        try (var client = clientBuilder.build();
                var server = serverBuilder.build()) {
            serverRef.set(server);
            server.start();
            client.start();
            var connected = new TestSignal<Connection>();
            client.connect(
                    20,
                    "127.0.0.1",
                    server.localAddress().getPort(),
                    slots,
                    new ConnectCallback() {
                        public void onSuccess(Connection c) {
                            connected.complete(c);
                        }

                        public void onFailure(Throwable failure) {
                            connected.completeExceptionally(failure);
                        }
                    });
            connected.get(10, TimeUnit.SECONDS);
            run(client, body, warmup, window);
            var before = Metrics.read();
            var latency = run(client, body, requests, window);
            var after = Metrics.read();
            double seconds = (after.elapsed - before.elapsed) / 1e9;
            double allocated =
                    before.allocated < 0 || after.allocated < 0
                            ? -1
                            : (after.allocated - before.allocated) / (double) requests;
            Arrays.sort(latency);
            System.out.printf(
                    Locale.ROOT,
                    "{\"requests\":%d,\"warmup\":%d,\"body_bytes\":%d,\"slots\":%d,\"in_flight\":%d,\"consolidate_flush\":%s,\"diagnostics\":%s,\"calls_per_second\":%.2f,\"p50_us\":%.2f,\"p95_us\":%.2f,\"p99_us\":%.2f,\"process_allocated_bytes_per_call\":%.2f,\"gc_count\":%d,\"gc_millis\":%d,\"process_cpu_seconds\":%.4f,\"seconds\":%.4f,\"errors\":0}%n",
                    requests,
                    warmup,
                    bytes,
                    slots,
                    window,
                    flush,
                    diagnostics,
                    requests / seconds,
                    percentile(latency, .50),
                    percentile(latency, .95),
                    percentile(latency, .99),
                    allocated,
                    after.gcCount - before.gcCount,
                    after.gcMillis - before.gcMillis,
                    (after.cpuNanos - before.cpuNanos) / 1e9,
                    seconds);
        } finally {
            body.release();
        }
    }
}
