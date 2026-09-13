package cn.managame.runtime.execution;

import cn.managame.runtime.clock.GameClock;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.context.CommandHandlerInvocation;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.context.MetadataPropagator;
import cn.managame.runtime.diagnostics.ExecutionDomainMetrics;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.HandlerExceptionHandler;
import cn.managame.runtime.diagnostics.RouteDiagnostics;
import cn.managame.runtime.diagnostics.RuntimeMetrics;
import cn.managame.runtime.diagnostics.RuntimeShutdownException;
import cn.managame.runtime.diagnostics.ShutdownReport;
import cn.managame.runtime.diagnostics.SlowTask;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.Route;
import cn.managame.runtime.route.RouteKeyResolver;
import cn.managame.runtime.route.RouteType;

import java.time.*;
import java.util.*;
import java.util.function.*;

/**
 * OGBS v0.1 execution runtime. Build once, submit decoded triggers, close outside a handler.
 * Business methods are synchronous void methods; virtual-thread blocking retains their Route.
 */
public final class GameRuntime implements AutoCloseable {
    private final ProtocolRegistry protocols;
    private final RuntimeBindings bindings;
    private final GameClock clock;
    private final MetadataPropagator propagator;
    private final RouteRuntime routes;
    private final GameScheduler scheduler;
    private final Duration shutdownTimeout;
    private GameRuntime(Builder builder, RuntimeBindings bindings) {
        this.shutdownTimeout = builder.shutdownTimeout;
        this.protocols = builder.protocols; this.bindings = bindings;
        this.clock = builder.clock; this.propagator = builder.propagator;
        routes = new RouteRuntime(this, builder.errors, builder.domainLimits, builder.routeShards, builder.executionDomains,
                builder.slowTaskThreshold, builder.slowTaskCapacity);
        GameScheduler createdScheduler = null;
        try {
            createdScheduler = new GameScheduler(this, builder.timerOptions);
            scheduler = createdScheduler;
            bindings.crons().forEach(scheduler::cron);
            scheduler.start(builder.automaticScheduling);
        } catch (Throwable failure) {
            // Release successfully created resources in reverse order, even if construction was partial.
            if (createdScheduler != null) {
                try { createdScheduler.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            try {
                ShutdownReport report = routes.abortStartup(new ShutdownDeadline(builder.shutdownTimeout));
                if (!report.terminated()) failure.addSuppressed(new RuntimeShutdownException(report));
            } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public static Builder builder() { return new Builder(); }
    public GameClock clock() { return clock; }
    public ProtocolRegistry protocols() { return protocols; }
    public CommandRegistry commands() { return bindings.commands(); }
    public EventRegistry events() { return bindings.events(); }
    public Map<String, ExecutionDomainMetrics> executionDomains() { return routes.executionDomains(); }
    /** Query one active entity without scanning other queues. Empty when it has drained. */
    public Optional<RouteDiagnostics> routeDiagnostics(Route route) { return routes.diagnostics(Objects.requireNonNull(route)); }
    /** Scan active routes, returning at most limit sorted by greatest running/queue delay. Use on demand. */
    public List<RouteDiagnostics> routeDiagnostics(int limit) { return routes.diagnostics(limit); }
    /** Merge the newest samples across domains, bounded by maxSamples and returned oldest first. */
    public List<SlowTask> recentSlowTasks() { return routes.recentSlowTasks(); }
    /** Samples belonging to the execution domain of this type; other domains cannot evict them. */
    public List<SlowTask> recentSlowTasks(Class<? extends RouteType> type) { return routes.recentSlowTasks(type); }
    public int activeRoutes() { return routes.activeRoutes(); }
    public RuntimeMetrics metrics() {
        return routes.metrics(scheduler.scheduledTimers(), scheduler.rejectedTimers());
    }
    public RouteTask dispatch(Class<? extends RouteType> routeType, long key, Runnable action) {
        return dispatch(new HandlerContext(new Route(routeType, key), childMetadata()), "dispatch", action);
    }
    RouteTask dispatch(HandlerContext context, String source, Runnable action) {
        return routes.submit(context, source, action, false, null);
    }
    RouteTask dispatchCallback(HandlerContext context, Runnable action, RouteTask completion) {
        return routes.submit(context, "callback", action, true, completion);
    }
    RouteTask task(Route route) { return routes.task(Objects.requireNonNull(route)); }
    HandlerContext callbackContext(Route route) {
        routes.requireType(route.type());
        return new HandlerContext(route, childMetadata());
    }
    public RouteTask command(Object request, Object connection) {
        return command(request, connection, 0);
    }
    /** Submit a command with explicit response correlation and no extension metadata. */
    public RouteTask command(Object request, Object connection, int requestId) {
        return command(new CommandHandlerInvocation(request, connection, requestId, Metadata.empty()));
    }
    public RouteTask command(CommandHandlerInvocation invocation) {
        Objects.requireNonNull(invocation);
        HandlerBindings.CommandBinding binding;
        HandlerBindings.PreparedCommand resolved;
        try {
            binding = bindings.commands().require(invocation.request().getClass());
            resolved = binding.prepare(invocation);
        } catch (Throwable error) {
            throw report("command resolution", null, new HandlerException("command resolution", null, error,
                    invocation, HandlerException.CommandStage.RESOLUTION));
        }
        var context = new CommandContext(resolved.route(), binding.protocol(), invocation);
        try {
            return dispatch(context, binding.name(), () -> {
                try { binding.invoke(invocation, resolved); }
                catch (Throwable error) {
                    throw new HandlerException(binding.name(), context, error, invocation, HandlerException.CommandStage.EXECUTION);
                }
            });
        } catch (Throwable error) {
            throw report("command admission", context, new HandlerException("command admission", context, error,
                    invocation, HandlerException.CommandStage.ADMISSION));
        }
    }
    /** Same-route subscribers run inline. Failures are isolated; remote routes are never awaited. */
    public void publish(Object event) {
        Objects.requireNonNull(event);
        List<HandlerBindings.EventBinding> subscribers;
        try { subscribers = bindings.events().bindings(event.getClass()); }
        catch (Throwable error) { throw report("event lookup", null, error); }
        HandlerContext parent = HandlerContexts.currentOrNull();
        for (var subscriber : subscribers) {
            HandlerContext context = null;
            try {
                Metadata metadata = childMetadata();
                context = new HandlerContext(subscriber.route(event), metadata);
                if (HandlerContexts.belongsTo(this) && parent.route().equals(context.route())) {
                    HandlerContexts.run(this, context, () -> subscriber.invoke(event));
                } else {
                    dispatch(context, subscriber.name(), () -> subscriber.invoke(event));
                }
            } catch (Throwable error) { report(subscriber.name(), context, error); }
        }
    }
    public TimerTask schedule(Class<? extends RouteType> routeType, long key, Duration delay, Runnable callback) {
        routes.requireType(routeType);
        Objects.requireNonNull(delay); Objects.requireNonNull(callback);
        if (delay.isNegative()) throw new IllegalArgumentException("Negative timer delay");
        HandlerContext context = new HandlerContext(new Route(routeType, key), childMetadata());
        RouteTask completion = task(context.route());
        return scheduler.schedule(delay, () -> routes.submit(context, "timer", callback, false, completion), completion);
    }
    /** Calendar deadline, intentionally affected by changes to GameClock.now(). */
    public TimerTask scheduleAt(Class<? extends RouteType> routeType, long key, Instant deadline, Runnable callback) {
        routes.requireType(routeType);
        Objects.requireNonNull(deadline); Objects.requireNonNull(callback);
        HandlerContext context = new HandlerContext(new Route(routeType, key), childMetadata());
        RouteTask completion = task(context.route());
        return scheduler.schedule(deadline, () -> routes.submit(context, "timer", callback, false, completion), completion);
    }
    /** Deterministic scheduling hook for a MutableGameClock; also safe with automatic polling. */
    public int runDueTimers() { return scheduler.runDue(); }
    public <T> RuntimeCallback<T> callback(Consumer<? super T> success) { return callback(success, null); }
    public <T> RuntimeCallback<T> callback(Consumer<? super T> success, Consumer<? super Throwable> failure) {
        if (!HandlerContexts.belongsTo(this)) throw new IllegalStateException("Callback requires a current handler in this runtime");
        return callback(new CallbackDefinition<>(HandlerContexts.current().route(), success, failure));
    }
    public <T> RuntimeCallback<T> callback(CallbackDefinition<T> definition) {
        Objects.requireNonNull(definition);
        return new RuntimeCallback<>(this, callbackContext(definition.route()),
                definition.onSuccess(), definition.onFail());
    }
    private Metadata childMetadata() {
        HandlerContext parent = HandlerContexts.currentOrNull();
        return Objects.requireNonNull(propagator.propagate(parent == null ? Metadata.empty() : parent.metadata()),
                "MetadataPropagator returned null");
    }
    HandlerException report(String source, HandlerContext context, Throwable cause) { return routes.report(source, context, cause); }
    /** Stop admission and wait up to one shared deadline. Timed-out tasks keep their Route ownership. */
    public ShutdownReport close(Duration timeout) {
        ShutdownDeadline deadline = new ShutdownDeadline(timeout);
        if (HandlerContexts.belongsTo(this)) throw new IllegalStateException("Cannot close runtime from its own handler");
        scheduler.close();
        return routes.close(deadline);
    }
    @Override public void close() {
        ShutdownReport report = close(shutdownTimeout);
        if (!report.terminated()) throw new RuntimeShutdownException(report);
    }
    public static final class Builder {
        private ProtocolRegistry protocols = ProtocolRegistry.builder().build();
        private ParameterResolverRegistry parameters = ParameterResolverRegistry.builder().build();
        private GameClock clock = GameClock.system(ZoneId.of("UTC"));
        private MetadataPropagator propagator = MetadataPropagator.copy();
        private HandlerExceptionHandler errors = error -> System.getLogger(GameRuntime.class.getName())
                .log(System.Logger.Level.ERROR, error.getMessage(), error);
        private boolean automaticScheduling = true;
        private DomainLimits domainLimits = DomainLimits.defaults();
        private int routeShards = 64;
        private TimerOptions timerOptions = TimerOptions.defaults();
        private Duration shutdownTimeout = Duration.ofSeconds(30);
        private Duration slowTaskThreshold = Duration.ZERO;
        private int slowTaskCapacity;
        private final List<Object> handlers = new ArrayList<>();
        private final Map<Class<?>, HandlerBindings.RouteResolver> resolvers = new HashMap<>();
        private final Map<Class<? extends RouteType>, HandlerBindings.RouteResolver> defaults = new HashMap<>();
        private final Map<Class<? extends RouteType>, ExecutionDomain> executionDomains = new LinkedHashMap<>();
        private final Set<Class<?>> eventTypes = new HashSet<>();
        /** Once configured, every used route type must have an explicit domain binding. */
        public Builder executionDomain(Class<? extends RouteType> type, ExecutionDomain domain) {
            Objects.requireNonNull(type); Objects.requireNonNull(domain);
            if (executionDomains.putIfAbsent(type, domain) != null)
                throw new IllegalArgumentException("Duplicate execution domain binding: " + type.getName());
            return this;
        }
        /** Opt-in recording with maxSamples per execution domain; zero disables it. No business-thread logging. */
        public Builder slowTaskDiagnostics(Duration threshold, int maxSamples) {
            Objects.requireNonNull(threshold);
            if (threshold.isNegative()) throw new IllegalArgumentException("Negative slow-task threshold");
            threshold.toNanos();
            if (maxSamples < 0 || maxSamples > 100_000) throw new IllegalArgumentException("Slow-task capacity must be 0..100000");
            slowTaskThreshold = threshold; slowTaskCapacity = maxSamples; return this;
        }
        public Builder shutdownTimeout(Duration value) {
            new ShutdownDeadline(value); // Validate before changing builder state.
            shutdownTimeout = value; return this;
        }
        /** Capacities for the default domain; explicit domains configure their own limits. */
        public Builder domainLimits(DomainLimits value) { domainLimits = Objects.requireNonNull(value); return this; }
        /** Queue-management shards for the default domain. */
        public Builder routeShards(int count) {
            if (count < 1 || count > 4096) throw new IllegalArgumentException("routeShards must be 1..4096");
            routeShards = count; return this;
        }
        public Builder timerOptions(TimerOptions value) { timerOptions = Objects.requireNonNull(value); return this; }
        /** Compatibility bridge: prefer domainLimits, routeShards and timerOptions. */
        @Deprecated(forRemoval = true)
        public Builder limits(RuntimeLimits value) {
            Objects.requireNonNull(value);
            domainLimits = value.domainLimits(); routeShards = value.routeShards(); timerOptions = value.timerOptions();
            return this;
        }
        public Builder protocols(ProtocolRegistry value) { protocols = Objects.requireNonNull(value); return this; }
        public Builder parameters(ParameterResolverRegistry value) { parameters = Objects.requireNonNull(value); return this; }
        public Builder clock(GameClock value) { clock = Objects.requireNonNull(value); return this; }
        public Builder metadataPropagator(MetadataPropagator value) { propagator = Objects.requireNonNull(value); return this; }
        public Builder exceptionHandler(HandlerExceptionHandler value) { errors = Objects.requireNonNull(value); return this; }
        public Builder automaticScheduling(boolean value) { automaticScheduling = value; return this; }
        public Builder handler(Object value) { handlers.add(Objects.requireNonNull(value)); return this; }
        /** Explicit event declaration validates event parameters without imposing a runtime base class. */
        public Builder eventType(Class<?> type) {
            Objects.requireNonNull(type);
            if (type.isPrimitive() || type.isArray() || type == Object.class || HandlerContext.class.isAssignableFrom(type))
                throw new IllegalArgumentException("Invalid event type");
            if (!eventTypes.add(type)) throw new IllegalArgumentException("Duplicate event type");
            return this;
        }
        @SuppressWarnings("unchecked")
        public <T> Builder routeKeyResolver(Class<T> source, RouteKeyResolver<? super T> resolver) {
            Objects.requireNonNull(source); Objects.requireNonNull(resolver);
            if (resolvers.putIfAbsent(resolver.getClass(), new HandlerBindings.RouteResolver(source,
                    (RouteKeyResolver<Object>) resolver)) != null) throw new IllegalArgumentException("Duplicate RouteKeyResolver");
            return this;
        }
        @SuppressWarnings("unchecked")
        public <T> Builder defaultRoute(Class<? extends RouteType> routeType, Class<T> source, RouteKeyResolver<? super T> resolver) {
            Objects.requireNonNull(routeType); Objects.requireNonNull(source); Objects.requireNonNull(resolver);
            if (defaults.putIfAbsent(routeType, new HandlerBindings.RouteResolver(source,
                    (RouteKeyResolver<Object>) resolver)) != null) throw new IllegalArgumentException("Duplicate default route");
            return this;
        }
        private void validateExecutionDomains(RuntimeBindings bindings) {
            if (executionDomains.isEmpty()) return;
            Map<String, ExecutionDomain> names = new HashMap<>();
            long capacity = 0;
            for (ExecutionDomain domain : new HashSet<>(executionDomains.values())) {
                if (names.putIfAbsent(domain.name(), domain) != null)
                    throw new IllegalArgumentException("Duplicate execution domain name: " + domain.name());
                capacity += (long) domain.limits().maxTasks() + domain.limits().callbackReserve();
            }
            if (capacity > Integer.MAX_VALUE) throw new IllegalArgumentException("Combined domain capacity exceeds int range");
            for (Class<? extends RouteType> type : bindings.routeTypes())
                if (!executionDomains.containsKey(type))
                    throw new IllegalArgumentException("Missing execution domain: " + type.getName());
        }
        public GameRuntime build() {
            RuntimeBindings bindings = new HandlerBinder(protocols, parameters, resolvers, defaults).bind(handlers, eventTypes);
            validateExecutionDomains(bindings);
            if (bindings.crons().size() > timerOptions.maxTimers())
                throw new IllegalArgumentException("Cron registrations exceed timer capacity");
            // Validate all expressions against the configured clock before allocating runtime resources.
            bindings.crons().forEach(cron -> cron.expression().nextAfter(clock.now(), clock.zoneId()));
            return new GameRuntime(this, bindings);
        }
    }
}
