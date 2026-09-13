package cn.managame.runtime.execution;

import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import static cn.managame.runtime.execution.BytecodeFixtures.*;

/** Same production command path in both variants; a benchmark-local Binder selects the generated invoker. */
public final class BytecodeComparison {
    private static final int BATCH = 4096;
    private static volatile long sink;
    private static final com.sun.management.ThreadMXBean ALLOCATION =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private record Setup(GameRuntime runtime, Target target) implements AutoCloseable {
        public void close() { runtime.close(); }
    }

    static Setup setup(int arity, int routes) {
        Target target = arity == 2 ? new Target2(routes) : new Target4(routes);
        var protocols = ProtocolRegistry.builder();
        for (int i = 0; i < 32; i++) protocols.register(i, ProtocolType.REQUEST, requestType(i));
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(Id.class, inv -> new Id(((Payload) inv.request()).route))
                .register(Extra.class, inv -> (Extra) inv.connection()).build();
        GameRuntime runtime = GameRuntime.builder().protocols(protocols.build()).parameters(parameters)
                .defaultRoute(Player.class, Id.class, Id::value).handler(target).automaticScheduling(false)
                .executionDomain(Player.class, ExecutionDomain.platform("benchmark").threads(4).tasksPerTurn(32).build())
                .exceptionHandler(error -> System.err.println(error)).build();
        return new Setup(runtime, target);
    }

    static HandlerInvokers.CommandInvoker[] invokers(Setup setup, int count, String flavor) {
        var invokers = new HandlerInvokers.CommandInvoker[count];
        for (int i = 0; i < count; i++) {
            invokers[i] = setup.runtime.commands().require(requestType(i)).invoker();
            boolean generated = invokers[i].getClass().getName().contains("GeneratedCommand");
            if (generated != flavor.equals("bytecode")) throw new AssertionError("Wrong Binder on classpath: " + invokers[i].getClass());
        }
        return invokers;
    }

    static long currentAllocated() {
        return ALLOCATION.isThreadAllocatedMemoryEnabled() ? ALLOCATION.getCurrentThreadAllocatedBytes() : -1;
    }
    static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum(); }

    static void micro(String flavor, int arity, int protocols, int millis, int warmups, int samples) throws Throwable {
        try (Setup setup = setup(arity, 64)) {
            var invokers = invokers(setup, protocols, flavor);
            var inputs = new CommandHandlerInvocation[1024];
            Id id = new Id(7);
            Extra extra = new Extra(11);
            for (int i = 0; i < inputs.length; i++)
                inputs[i] = new CommandHandlerInvocation(request(i & (protocols - 1), i, 7, i + 1, 0), extra, Metadata.empty());
            long expected = 0;
            for (int i = 0; i < inputs.length; i++) {
                invokers[i & (protocols - 1)].invoke(inputs[i], id);
                expected += (7L ^ (i + 1)) + (arity == 4 ? 22 : 0) + (i & (protocols - 1));
            }
            if (Arrays.stream(setup.target.sums).sum() != expected) throw new AssertionError("Wrong micro business result");
            for (int round = -warmups; round < samples; round++) {
                long operations = 0, before = currentAllocated(), start = System.nanoTime();
                long deadline = start + TimeUnit.MILLISECONDS.toNanos(millis);
                do {
                    for (int j = 0; j < BATCH; j++) {
                        int index = (int) (operations + j);
                        invokers[index & (protocols - 1)].invoke(inputs[index & 1023], id);
                    }
                    operations += BATCH;
                } while (System.nanoTime() < deadline);
                long elapsed = System.nanoTime() - start, allocated = currentAllocated() - before;
                sink = Arrays.stream(setup.target.sums).sum();
                if (round >= 0) System.out.printf(Locale.ROOT,
                    "{\"kind\":\"micro\",\"flavor\":\"%s\",\"arity\":%d,\"protocols\":%d,\"sample\":%d,\"operations\":%d,\"nsPerOp\":%.3f,\"bytesPerOp\":%.3f}%n",
                    flavor, arity, protocols, round, operations, (double) elapsed / operations,
                    before < 0 ? -1 : (double) allocated / operations);
            }
        }
    }

    static int route(int index, int routes) {
        int mixed = index * 0x9e3779b9;
        mixed ^= mixed >>> 16;
        return (mixed & Integer.MAX_VALUE) % routes;
    }

    static void load(String flavor, int arity, int protocols, int messages, int warmups, int samples) throws Exception {
        int routes = 64, producers = 4, window = 64;
        Extra extra = new Extra(11);
        try (Setup setup = setup(arity, routes); ExecutorService callers = Executors.newFixedThreadPool(producers)) {
            invokers(setup, protocols, flavor); // Assert the full Runtime uses the requested backend before timing.
            for (int round = -warmups; round < samples; round++) {
                Arrays.fill(setup.target.sums, 0);
                setup.target.latencies = new long[messages];
                var ready = new CountDownLatch(producers);
                var startGate = new CountDownLatch(1);
                List<Future<?>> senders = new ArrayList<>();
                for (int p = 0; p < producers; p++) {
                    int producer = p;
                    senders.add(callers.submit(() -> {
                        ArrayDeque<RouteTask> pending = new ArrayDeque<>();
                        ready.countDown();
                        if (!startGate.await(10, TimeUnit.SECONDS)) throw new AssertionError("Producer start timeout");
                        for (int i = producer; i < messages; i += producers) {
                            int key = route(i, routes);
                            Payload payload = request(i & (protocols - 1), i, key, i + 1, System.nanoTime());
                            pending.addLast(setup.runtime.command(payload, extra));
                            if (pending.size() >= window) pending.removeFirst().get(30, TimeUnit.SECONDS);
                        }
                        while (!pending.isEmpty()) pending.removeFirst().get(30, TimeUnit.SECONDS);
                        return null;
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Producer ready timeout");
                long gcBefore = gcCount();
                long start = System.nanoTime();
                startGate.countDown();
                for (Future<?> sender : senders) sender.get(60, TimeUnit.SECONDS);
                long elapsed = System.nanoTime() - start;
                long gcs = gcCount() - gcBefore;
                long expected = 0;
                for (int i = 0; i < messages; i++)
                    expected += ((long) route(i, routes) ^ (i + 1)) + (arity == 4 ? 22 : 0) + (i & (protocols - 1));
                sink = Arrays.stream(setup.target.sums).sum();
                if (sink != expected) throw new AssertionError("Lost/duplicated/wrong business result: " + sink + " != " + expected);
                long[] latencies = setup.target.latencies;
                Arrays.sort(latencies);
                if (latencies[0] <= 0) throw new AssertionError("Missing latency");
                if (round >= 0) System.out.printf(Locale.ROOT,
                    "{\"kind\":\"load\",\"flavor\":\"%s\",\"arity\":%d,\"protocols\":%d,\"sample\":%d,\"messages\":%d,\"opsPerSecond\":%.3f,\"p50Micros\":%.3f,\"p99Micros\":%.3f,\"gcCount\":%d}%n",
                    flavor, arity, protocols, round, messages, messages * 1_000_000_000.0 / elapsed,
                    latencies[messages / 2] / 1000.0, latencies[Math.min(messages - 1, (int)Math.ceil(messages * .99) - 1)] / 1000.0, gcs);
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        String mode = args[0], flavor = args[1];
        int arity = Integer.parseInt(args[2]), protocols = Integer.parseInt(args[3]);
        if (!(arity == 2 || arity == 4) || protocols < 1 || protocols > 32 || Integer.bitCount(protocols) != 1
                || !(flavor.equals("bound") || flavor.equals("bytecode"))) throw new IllegalArgumentException("Invalid benchmark case");
        if (ALLOCATION.isThreadAllocatedMemorySupported()) ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        System.out.printf(Locale.ROOT, "{\"kind\":\"environment\",\"java\":\"%s\",\"vm\":\"%s\",\"processors\":%d,\"os\":\"%s\"}%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"));
        if (mode.equals("micro")) micro(flavor, arity, protocols, Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
        else if (mode.equals("load")) load(flavor, arity, protocols, Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]));
        else throw new IllegalArgumentException("Unknown mode");
    }
}
