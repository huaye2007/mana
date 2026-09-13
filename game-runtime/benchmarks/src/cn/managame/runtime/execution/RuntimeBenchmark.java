package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.RouteType;

import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import static cn.managame.runtime.execution.HandlerBindings.*;

/**
 * Dependency-free diagnostic benchmark. run.ps1 supplies separate JVM forks.
 * Not a substitute for a production load test or a statistically controlled JMH suite.
 */
public final class RuntimeBenchmark {
    interface Player extends RouteType {}
    private static volatile long sink;
    private static final int BATCH = 4096;
    record Id(long value) {}
    record Request(long value) {}
    record Extra(long value) {}

    static final class Target {
        long sum;
        public void command2(Id id, Request req) { sum += id.value() ^ req.value(); }
        public void command3(Id id, Request req, Extra extra) { sum += (id.value() ^ req.value()) + extra.value(); }
        public void command4(Id id, Request req, Extra first, Extra second) {
            sum += (id.value() ^ req.value()) + first.value() + second.value();
        }
        public void event(Request req) { sum += req.value(); }
        public void cron() { sum++; }
    }

    @FunctionalInterface interface Call { void invoke(CommandHandlerInvocation invocation, Object routeValue) throws Throwable; }

    static MethodHandle target(Target receiver, String shape) throws ReflectiveOperationException {
        Class<?>[] signature = switch (shape) {
            case "command2" -> new Class<?>[] {Id.class, Request.class};
            case "command3" -> new Class<?>[] {Id.class, Request.class, Extra.class};
            case "command4" -> new Class<?>[] {Id.class, Request.class, Extra.class, Extra.class};
            case "event" -> new Class<?>[] {Request.class};
            case "cron" -> new Class<?>[0];
            default -> throw new IllegalArgumentException(shape);
        };
        return MethodHandles.lookup().unreflect(Target.class.getDeclaredMethod(shape, signature)).bindTo(receiver);
    }

    // Retains the previous implementation's two arrays and resolve-once semantics as a baseline.
    static Call legacyCommand(MethodHandle target, List<ArgumentSlot> slots, int[] arguments) {
        MethodHandle handle = target.asSpreader(Object[].class, arguments.length)
                .asType(MethodType.methodType(void.class, Object[].class));
        return (invocation, route) -> {
            Object[] values = new Object[slots.size()];
            values[0] = route;
            for (int i = 1; i < values.length; i++) values[i] = slots.get(i).resolver().apply(invocation);
            Object[] args = new Object[arguments.length];
            for (int i = 0; i < args.length; i++) args[i] = values[arguments[i]];
            handle.invokeExact(args);
        };
    }

    static Call bind(String flavor, String shape, Target receiver) throws ReflectiveOperationException {
        MethodHandle target = target(receiver, shape);
        if (flavor.equals("direct")) {
            return switch (shape) {
                case "command2" -> (inv, id) -> receiver.command2((Id) id, (Request) inv.request());
                case "command3" -> (inv, id) -> receiver.command3((Id) id, (Request) inv.request(), (Extra) inv.connection());
                case "command4" -> (inv, id) -> receiver.command4((Id) id, (Request) inv.request(),
                        (Extra) inv.connection(), (Extra) inv.connection());
                case "event" -> (inv, id) -> receiver.event((Request) inv.request());
                case "cron" -> (inv, id) -> receiver.cron();
                default -> throw new IllegalArgumentException(shape);
            };
        }
        if (shape.equals("event")) {
            if (flavor.equals("bound")) {
                var invoker = HandlerInvokers.event(target);
                return (inv, id) -> invoker.invoke(inv.request());
            }
            MethodHandle handle = target.asSpreader(Object[].class, 1).asType(MethodType.methodType(void.class, Object[].class));
            return (inv, id) -> { handle.invokeExact(new Object[] {inv.request()}); };
        }
        if (shape.equals("cron")) {
            if (flavor.equals("bound")) {
                var invoker = HandlerInvokers.cron(target);
                return (inv, id) -> invoker.invoke();
            }
            MethodHandle handle = target.asSpreader(Object[].class, 0).asType(MethodType.methodType(void.class, Object[].class));
            return (inv, id) -> { handle.invokeExact(new Object[0]); };
        }
        List<ArgumentSlot> slots = new ArrayList<>();
        slots.add(new ArgumentSlot(inv -> { throw new AssertionError("Route value must be reused"); }, true));
        slots.add(new ArgumentSlot(CommandHandlerInvocation::request, true));
        if (!shape.equals("command2")) slots.add(new ArgumentSlot(CommandHandlerInvocation::connection, false));
        int[] arguments = switch (shape) {
            case "command2" -> new int[] {0, 1};
            case "command3" -> new int[] {0, 1, 2};
            case "command4" -> new int[] {0, 1, 2, 2};
            default -> throw new IllegalArgumentException(shape);
        };
        if (flavor.equals("legacy")) return legacyCommand(target, slots, arguments);
        var invoker = HandlerInvokers.command(target, slots, arguments, 0);
        return invoker::invoke;
    }

    static void micro(String flavor, String shape, int targets, int millis, int warmups, int samples) throws Throwable {
        if (Integer.bitCount(targets) != 1) throw new IllegalArgumentException("targets must be a power of two");
        Target[] receivers = new Target[targets];
        Call[] calls = new Call[targets];
        for (int i = 0; i < targets; i++) { receivers[i] = new Target(); calls[i] = bind(flavor, shape, receivers[i]); }
        Id id = new Id(7);
        Extra extra = new Extra(11);
        CommandHandlerInvocation[] inputs = new CommandHandlerInvocation[1024];
        for (int i = 0; i < inputs.length; i++)
            inputs[i] = new CommandHandlerInvocation(new Request(i + 1), extra, Metadata.empty());
        // Validate against direct business invocation before timing.
        Target reference = new Target();
        Call direct = bind("direct", shape, reference);
        for (int i = 0; i < inputs.length; i++) {
            calls[i & (targets - 1)].invoke(inputs[i], id);
            direct.invoke(inputs[i], id);
        }
        long actual = Arrays.stream(receivers).mapToLong(receiver -> receiver.sum).sum();
        if (actual != reference.sum) throw new AssertionError("Different business result");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocation = bean.isThreadAllocatedMemorySupported();
        if (allocation && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        for (int round = -warmups; round < samples; round++) {
            long operations = 0;
            long before = allocation ? bean.getCurrentThreadAllocatedBytes() : -1;
            long start = System.nanoTime();
            long deadline = start + TimeUnit.MILLISECONDS.toNanos(millis);
            do {
                for (int j = 0; j < BATCH; j++) {
                    int index = (int) (operations + j);
                    calls[index & (targets - 1)].invoke(inputs[index & 1023], id);
                }
                operations += BATCH;
            } while (System.nanoTime() < deadline);
            long elapsed = System.nanoTime() - start;
            long allocated = allocation ? bean.getCurrentThreadAllocatedBytes() - before : -1;
            sink = Arrays.stream(receivers).mapToLong(receiver -> receiver.sum).sum();
            if (round >= 0) {
                System.out.printf(Locale.ROOT,
                        "{\"kind\":\"micro\",\"case\":\"%s-%s\",\"targets\":%d,\"sample\":%d,\"operations\":%d,\"nsPerOp\":%.3f,\"bytesPerOp\":%s}%n",
                        flavor, shape, targets, round, operations, (double) elapsed / operations,
                        allocation ? String.format(Locale.ROOT, "%.3f", (double) allocated / operations) : "null");
            }
        }
    }

    record Envelope(int index, int route, long value, long sentAt) {}
    @Handler(routeType = Player.class)
    static final class LoadHandler {
        final long[] sums;
        long[] latencies;
        LoadHandler(int routes) { sums = new long[routes]; }
        @HandlerMethod public void message(Id id, Envelope message) {
            sums[(int) id.value()] += message.value() ^ id.value();
            latencies[message.index()] = System.nanoTime() - message.sentAt();
        }
    }

    static void load(String mode, int routes, int producers, int window, int messages, int warmups, int samples) throws Exception {
        LoadHandler handler = new LoadHandler(routes);
        var protocols = ProtocolRegistry.builder().register(1, ProtocolType.REQUEST, Envelope.class).build();
        var parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(Id.class, inv -> new Id(((Envelope) inv.request()).route())).build();
        try (GameRuntime runtime = GameRuntime.builder().protocols(protocols).parameters(parameters)
                .defaultRoute(Player.class, Id.class, Id::value).handler(handler).automaticScheduling(false)
                .exceptionHandler(error -> { throw new AssertionError(error); }).build();
             ExecutorService callers = Executors.newFixedThreadPool(producers)) {
            for (int round = -warmups; round < samples; round++) {
                Arrays.fill(handler.sums, 0);
                handler.latencies = new long[messages];
                CountDownLatch ready = new CountDownLatch(producers), startGate = new CountDownLatch(1);
                List<Future<?>> senders = new ArrayList<>();
                for (int producer = 0; producer < producers; producer++) {
                    int partition = producer;
                    senders.add(callers.submit(() -> {
                        ArrayDeque<RouteTask> outstanding = new ArrayDeque<>();
                        ready.countDown();
                        if (!startGate.await(10, TimeUnit.SECONDS)) throw new AssertionError("Load start timed out");
                        for (int i = partition; i < messages; i += producers) {
                            int route = i % routes;
                            Envelope envelope = new Envelope(i, route, i + 1, System.nanoTime());
                            RouteTask task;
                            if (mode.equals("command")) task = runtime.command(envelope, null);
                            else {
                                Id id = new Id(route);
                                task = runtime.dispatch(Player.class, route, () -> handler.message(id, envelope));
                            }
                            outstanding.addLast(task);
                            if (outstanding.size() >= window) outstanding.removeFirst().get(30, TimeUnit.SECONDS);
                        }
                        while (!outstanding.isEmpty()) outstanding.removeFirst().get(30, TimeUnit.SECONDS);
                        return null;
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Load producers did not start");
                long start = System.nanoTime();
                startGate.countDown();
                for (Future<?> sender : senders) sender.get(60, TimeUnit.SECONDS);
                long elapsed = System.nanoTime() - start;
                long expected = 0;
                for (int i = 0; i < messages; i++) expected += (long) (i + 1) ^ (i % routes);
                sink = Arrays.stream(handler.sums).sum();
                if (sink != expected) throw new AssertionError("Lost/duplicated load messages");
                Arrays.sort(handler.latencies);
                if (handler.latencies[0] <= 0) throw new AssertionError("Missing latency");
                if (round >= 0) System.out.printf(Locale.ROOT,
                        "{\"kind\":\"load\",\"case\":\"%s\",\"routes\":%d,\"producers\":%d,\"window\":%d,\"messages\":%d,\"sample\":%d,\"opsPerSecond\":%.3f,\"p50Micros\":%.3f,\"p99Micros\":%.3f}%n",
                        mode, routes, producers, window, messages, round, messages * 1_000_000_000.0 / elapsed,
                        handler.latencies[messages / 2] / 1000.0,
                        handler.latencies[Math.min(messages - 1, (int) Math.ceil(messages * 0.99) - 1)] / 1000.0);
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        // mode flavor/shape targets millis warmups samples OR load mode routes producers window messages warmups samples
        System.out.printf(Locale.ROOT, "{\"kind\":\"environment\",\"java\":\"%s\",\"vm\":\"%s\",\"processors\":%d,\"os\":\"%s\"}%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"));
        if (args[0].equals("micro")) micro(args[1], args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                Integer.parseInt(args[5]), Integer.parseInt(args[6]));
        else load(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                Integer.parseInt(args[5]), Integer.parseInt(args[6]), Integer.parseInt(args[7]));
    }
}
