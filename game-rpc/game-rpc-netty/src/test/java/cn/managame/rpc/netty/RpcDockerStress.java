package cn.managame.rpc.netty;

import cn.managame.rpc.core.RpcHandler;
import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.core.RpcResult;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;

import cn.managame.network.*;
import cn.managame.network.netty.connection.NettyAccess;
import cn.managame.rpc.core.*;

import com.sun.net.httpserver.HttpServer;

import io.netty.buffer.*;

import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Standalone Linux/container workload. All scheduling and fault controls belong to this test. */
public final class RpcDockerStress {
    static final RpcError BUSINESS_ERROR = new RpcError(1001, "Test business rejection");
    static final AtomicLong sent = new AtomicLong(), completed = new AtomicLong();
    static final LongAdder ok = new LongAdder(),
            corrupt = new LongAdder(),
            duplicate = new LongAdder();
    static final ConcurrentHashMap<Integer, LongAdder> errors = new ConcurrentHashMap<>();
    static final AtomicLongArray latency =
            new AtomicLongArray(60001); // Successful RTT, 0.1 ms buckets.
    static final AtomicLongArray windowLatency = new AtomicLongArray(60001);
    static final AtomicBoolean stopping = new AtomicBoolean();
    static final List<RpcNode> nodes = new CopyOnWriteArrayList<>();
    static final LongAdder replyRejected = new LongAdder();
    static final AtomicLong rejectUntil = new AtomicLong();
    static final long processStart = System.nanoTime();

    static RpcNode.Builder builder(
            int id, String host, int port, boolean flush, RpcHandler handler) {
        return RpcNode.builder()
                .nodeId(id)
                .listen(host, port)
                .handler(handler)
                .defaultTimeout(Duration.ofSeconds(2))
                .consolidateFlush(flush)
                .reconnectDelay(Duration.ofMillis(200))
                .maxReconnectDelay(Duration.ofSeconds(2))
                .heartbeatInterval(Duration.ofSeconds(1))
                .heartbeatTimeout(Duration.ofSeconds(3));
    }

    static long pending() {
        long result = 0;
        for (var node : nodes) {
            var peer = node.peer(20);
            if (peer != null) result += peer.pendingCount();
        }
        return result;
    }

    static Map<String, Object> resources() {
        var m = new LinkedHashMap<String, Object>();
        var metrics = RpcLoadBenchmark.Metrics.read();
        var memory = ManagementFactory.getMemoryMXBean();
        m.put("elapsed_s", (System.nanoTime() - processStart) / 1e9);
        m.put("heap_used", memory.getHeapMemoryUsage().getUsed());
        m.put("netty_direct_reserved", PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory());
        m.put("allocated_bytes", metrics.allocated());
        m.put("cpu_ns", metrics.cpuNanos());
        m.put("gc_count", metrics.gcCount());
        m.put("gc_ms", metrics.gcMillis());
        m.put("platform_threads", ManagementFactory.getThreadMXBean().getThreadCount());
        try {
            for (var line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:"))
                    m.put("rss_bytes", Long.parseLong(line.split("\\s+")[1]) * 1024);
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    static double p99(AtomicLongArray histogram, boolean clear) {
        long[] buckets = new long[histogram.length()];
        long count = 0;
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = clear ? histogram.getAndSet(i, 0) : histogram.get(i);
            count += buckets[i];
        }
        long target = (long) Math.ceil(count * .99), sum = 0;
        if (target == 0) return 0;
        for (int i = 0; i < buckets.length; i++) {
            sum += buckets[i];
            if (sum >= target) return (i + 1) / 10.0;
        }
        return 6000.1;
    }

    static void sample(String role, boolean last) {
        var m = resources();
        int active = 0;
        for (var node : nodes) {
            for (int id = 10; id < 26; id++) {
                var peer = node.peer(id);
                if (peer == null) continue;
                for (int slot = 0; slot < peer.connectionCount(); slot++) {
                    var connection = peer.connection(slot);
                    if (connection != null && connection.isActive()) active++;
                }
            }
        }
        m.put("active_slots", active);
        var events = new TreeMap<String, Long>();
        for (var node : nodes)
            node.eventCounts().forEach((key, count) -> events.merge(key, count, Long::sum));
        m.put("events", events);
        m.put("role", role);
        m.put("final", last);
        m.put("sent", sent.get());
        m.put("completed", completed.get());
        m.put("ok", ok.sum());
        var failureCodes = new TreeMap<String, Long>();
        errors.forEach((code, count) -> failureCodes.put(code.toString(), count.sum()));
        m.put("errors", failureCodes);
        m.put("duplicate", duplicate.sum());
        m.put("corrupt", corrupt.sum());
        m.put("pending", pending());
        m.put("reply_rejected", replyRejected.sum());
        m.put("success_p99_ms", p99(last ? latency : windowLatency, !last));
        emit(m);
    }

    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            var parts = new ArrayList<String>();
            map.forEach((k, v) -> parts.add(json(k.toString()) + ":" + json(v)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\""
                + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"";
    }

    static void emit(Map<String, ?> message) {
        System.out.println(json(message));
    }

    static void server(boolean flush) throws Exception {
        var ref = new AtomicReference<RpcNode>();
        var server =
                builder(
                                20,
                                "0.0.0.0",
                                7000,
                                flush,
                                (c, m) -> {
                                    var q = (RpcRequest) m;
                                    sent.incrementAndGet();
                                    boolean reject =
                                            q.command() == 2
                                                    && q.body().getLong(q.body().readerIndex())
                                                                    % 100
                                                            == 0;
                                    RpcResponse response =
                                            reject
                                                    ? RpcResponse.error(
                                                            q.requestId(),
                                                            BUSINESS_ERROR,
                                                            "1000",
                                                            "300")
                                                    : new RpcResponse(
                                                            q.requestId(),
                                                            0,
                                                            q.metadata(),
                                                            q.body());
                                    if (ref.get().reply(c, response)) ok.increment();
                                    else replyRejected.increment();
                                    completed.incrementAndGet();
                                })
                        .readIdleTimeout(Duration.ofSeconds(10))
                        .build();
        ref.set(server);
        nodes.add(server);
        server.start();
        var http = HttpServer.create(new InetSocketAddress("0.0.0.0", 7001), 0);
        http.createContext(
                "/control",
                exchange -> {
                    String query = Objects.toString(exchange.getRequestURI().getQuery(), "");
                    int changed = 0;
                    for (int id = 10; id < 26; id++) {
                        var peer = server.peer(id);
                        if (peer == null) continue;
                        for (int slot = 0; slot < peer.connectionCount(); slot++) {
                            var c = peer.connection(slot);
                            if (c == null) continue;
                            if (query.equals("close")) {
                                c.close();
                                changed++;
                            } else if (query.startsWith("pauseRead=")) {
                                long millis = Long.parseLong(query.substring(10));
                                var channel = NettyAccess.channel(c);
                                channel.eventLoop()
                                        .execute(
                                                () -> {
                                                    channel.config().setAutoRead(false);
                                                    channel.eventLoop()
                                                            .schedule(
                                                                    () ->
                                                                            channel.config()
                                                                                    .setAutoRead(
                                                                                            true),
                                                                    millis,
                                                                    TimeUnit.MILLISECONDS);
                                                });
                                changed++;
                            }
                        }
                    }
                    byte[] body = json(Map.of("changed", changed)).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        http.start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    http.stop(0);
                                    server.close();
                                }));
        emit(
                Map.of(
                        "ready",
                        true,
                        "role",
                        "server",
                        "java",
                        System.getProperty("java.version"),
                        "os",
                        System.getProperty("os.name")));
        while (true) {
            Thread.sleep(5000);
            sample("server", false);
        }
    }

    static void recordResult(
            RpcResult result, int command, int bytes, int index, long token, long start) {
        if (result.isSuccess()) {
            var response = result.value();
            var body = response.body();
            if ((command == 2 && token % 100 == 0)
                    || body.readableBytes() != bytes
                    || body.getLong(body.readerIndex()) != token
                    || body.getByte(body.writerIndex() - 1) != (byte) index
                    || response.metadata().getInt((short) 1024) != index) corrupt.increment();
            ok.increment();
            int bucket = (int) Math.min(60000, (System.nanoTime() - start) / 100000);
            latency.incrementAndGet(bucket);
            windowLatency.incrementAndGet(bucket);
            return;
        }
        int code = result.error().code();
        errors.computeIfAbsent(code, k -> new LongAdder()).increment();
        if (code == 1001) {
            if (command != 2
                    || token % 100 != 0
                    || !Arrays.equals(new String[] {"1000", "300"}, result.value().errorArgs()))
                corrupt.increment();
        } else rejectUntil.set(System.nanoTime() + 1_000_000);
    }

    static void client(
            String host,
            int seconds,
            int bytes,
            int workers,
            int window,
            int nodeCount,
            boolean flush,
            int command)
            throws Exception {
        for (int i = 0; i < nodeCount; i++) {
            var node = builder(10 + i, "0.0.0.0", 0, flush, (c, m) -> {}).build();
            nodes.add(node);
            node.start();
            var connected = new TestSignal<Connection>();
            node.connect(
                    20,
                    host,
                    7000,
                    4,
                    new ConnectCallback() {
                        public void onSuccess(Connection c) {
                            connected.complete(c);
                        }

                        public void onFailure(Throwable e) {
                            connected.completeExceptionally(e);
                        }
                    });
            connected.get(20, TimeUnit.SECONDS);
        }
        var permits = new Semaphore(window);
        var threads = new ArrayList<Thread>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        for (int i = 0; i < workers; i++) {
            final int index = i;
            threads.add(
                    Thread.ofPlatform()
                            .name("load-" + i)
                            .start(
                                    () -> {
                                        var node = nodes.get(index % nodes.size());
                                        var payload = Unpooled.buffer(bytes).writeZero(bytes);
                                        payload.setByte(bytes - 1, index);
                                        var options =
                                                RpcOptions.builder()
                                                        .routeKey(index + 1L)
                                                        .putInt((short) 1024, index)
                                                        .build();
                                        long serial = index;
                                        try {
                                            while (!stopping.get()
                                                    && System.nanoTime() < deadline) {
                                                long delay = rejectUntil.get() - System.nanoTime();
                                                if (delay > 0)
                                                    LockSupport.parkNanos(
                                                            Math.min(delay, 1_000_000));
                                                if (!permits.tryAcquire(100, TimeUnit.MILLISECONDS))
                                                    continue;
                                                long token = serial;
                                                serial += workers;
                                                payload.setLong(0, token);
                                                long start = System.nanoTime();
                                                var notified = new AtomicBoolean();
                                                sent.incrementAndGet();
                                                try {
                                                    node.call(
                                                            20,
                                                            command,
                                                            payload,
                                                            options,
                                                            result -> {
                                                                if (!notified.compareAndSet(
                                                                        false, true)) {
                                                                    duplicate.increment();
                                                                    return;
                                                                }
                                                                try {
                                                                    recordResult(
                                                                            result, command, bytes,
                                                                            index, token, start);
                                                                } catch (Throwable failure) {
                                                                    corrupt.increment();
                                                                } finally {
                                                                    completed.incrementAndGet();
                                                                    permits.release();
                                                                }
                                                            });
                                                } catch (Throwable failure) {
                                                    if (notified.compareAndSet(false, true)) {
                                                        completed.incrementAndGet();
                                                        permits.release();
                                                    }
                                                    corrupt.increment();
                                                }
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        } finally {
                                            payload.release();
                                        }
                                    }));
        }
        emit(
                Map.of(
                        "ready",
                        true,
                        "role",
                        "client",
                        "seconds",
                        seconds,
                        "bytes",
                        bytes,
                        "workers",
                        workers,
                        "window",
                        window,
                        "nodes",
                        nodeCount,
                        "flush",
                        flush,
                        "java",
                        System.getProperty("java.version")));
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(5000);
                sample("client", false);
            }
            for (var thread : threads) thread.join(5000);
            long drain = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (completed.get() != sent.get() && System.nanoTime() < drain) Thread.sleep(10);
            sample("client", true);
            if (completed.get() != sent.get()
                    || pending() != 0
                    || corrupt.sum() != 0
                    || duplicate.sum() != 0)
                throw new IllegalStateException(
                        "Completion, ownership or payload invariant failed");
        } finally {
            stopping.set(true);
            for (var node : nodes.reversed()) node.close();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("server")) server(Boolean.parseBoolean(args[1]));
        else
            client(
                    args[1],
                    Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]),
                    Integer.parseInt(args[6]),
                    Boolean.parseBoolean(args[7]),
                    Integer.parseInt(args[8]));
    }
}
