package cn.managame.runtime.execution;


import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable resource configuration. Reusing one instance for several route types shares
 * resources within one Runtime. Each Runtime creates and owns its own workers.
 */
public final class ExecutionDomain {
    public enum Mode { PLATFORM, VIRTUAL, CUSTOM }
    public enum Scheduling {
        /** Independent entity queues share available workers; no physical thread affinity. */
        BALANCED,
        /** A stable key hash selects one dedicated platform worker for this domain lifetime. */
        KEY_AFFINITY,
        CUSTOM
    }
    private final String name;
    private final Mode mode;
    private final Scheduling scheduling;
    private final int concurrency, tasksPerTurn, routeShards;
    private final DomainLimits limits;
    private final Supplier<? extends RouteDispatcher> dispatcherFactory;
    private ExecutionDomain(Builder b) {
        name = b.name; mode = b.mode; concurrency = b.concurrency; tasksPerTurn = b.tasksPerTurn;
        routeShards = b.routeShards;
        limits = new DomainLimits(b.maxTasks, b.maxTasksPerRoute, b.callbackReserve, b.callbackReservePerRoute);
        scheduling = b.scheduling; dispatcherFactory = b.dispatcherFactory;
    }
    public static Builder platform(String name) { return new Builder(name, Mode.PLATFORM); }
    public static Builder virtual(String name) { return new Builder(name, Mode.VIRTUAL); }
    public static Builder custom(String name, Supplier<? extends RouteDispatcher> factory) {
        Builder builder = new Builder(name, Mode.CUSTOM);
        builder.dispatcherFactory = Objects.requireNonNull(factory);
        builder.scheduling = Scheduling.CUSTOM;
        return builder;
    }
    RouteDispatcher createDispatcher() {
        return mode == Mode.CUSTOM
                ? new GuardedRouteDispatcher(Objects.requireNonNull(dispatcherFactory.get(), "Dispatcher factory returned null"))
                : new DomainScheduler(this);
    }
    public String name() { return name; }
    public Mode mode() { return mode; }
    public Scheduling scheduling() { return scheduling; }
    public int concurrency() { return concurrency; }
    public int tasksPerTurn() { return tasksPerTurn; }
    /** Admission capacity belongs exclusively to this execution domain. */
    public DomainLimits limits() { return limits; }
    /** Number of short-lived queue locks; independent of worker count or entity ownership. */
    public int routeShards() { return routeShards; }

    public static final class Builder {
        private final String name;
        private final Mode mode;
        private Scheduling scheduling = Scheduling.BALANCED;
        private int concurrency, tasksPerTurn = 64, routeShards = 64;
        private int maxTasks, maxTasksPerRoute, callbackReserve, callbackReservePerRoute;
        private Supplier<? extends RouteDispatcher> dispatcherFactory;
        private Builder(String name, Mode mode) {
            this.name = Objects.requireNonNull(name);
            if (name.isBlank()) throw new IllegalArgumentException("Blank execution domain name");
            this.mode = mode;
            concurrency = mode == Mode.CUSTOM ? 0 : mode == Mode.PLATFORM ? Runtime.getRuntime().availableProcessors() : 256;
            limits(DomainLimits.defaults());
        }
        public Builder scheduling(Scheduling value) {
            Objects.requireNonNull(value);
            if (mode == Mode.CUSTOM || value == Scheduling.CUSTOM)
                throw new IllegalArgumentException("Custom scheduling is configured through a dispatcher factory");
            if (mode == Mode.VIRTUAL && value == Scheduling.KEY_AFFINITY)
                throw new IllegalArgumentException("Key affinity requires a platform domain");
            scheduling = value; return this;
        }
        public Builder threads(int count) {
            if (mode != Mode.PLATFORM) throw new IllegalStateException("threads requires a platform domain");
            concurrency = positive(count); return this;
        }
        public Builder maxConcurrentRoutes(int count) {
            if (mode != Mode.VIRTUAL) throw new IllegalStateException("maxConcurrentRoutes requires a virtual domain");
            concurrency = positive(count); return this;
        }
        public Builder tasksPerTurn(int count) { tasksPerTurn = positive(count); return this; }
        public Builder limits(DomainLimits value) {
            Objects.requireNonNull(value);
            maxTasks = value.maxTasks(); maxTasksPerRoute = value.maxTasksPerRoute();
            callbackReserve = value.callbackReserve(); callbackReservePerRoute = value.callbackReservePerRoute();
            return this;
        }
        public Builder routeShards(int count) {
            if (count < 1 || count > 4096) throw new IllegalArgumentException("Route shards must be 1..4096");
            routeShards = count; return this;
        }
        public Builder maxTasks(int count) { maxTasks = positive(count); return this; }
        public Builder maxTasksPerRoute(int count) { maxTasksPerRoute = positive(count); return this; }
        public Builder callbackReserve(int total, int perRoute) {
            if (total < 0 || perRoute < 0) throw new IllegalArgumentException("Callback capacity reserves must be nonnegative");
            callbackReserve = total; callbackReservePerRoute = perRoute; return this;
        }
        public ExecutionDomain build() { return new ExecutionDomain(this); }
        private static int positive(int value) {
            if (value < 1) throw new IllegalArgumentException("Expected positive value");
            return value;
        }
    }
}
