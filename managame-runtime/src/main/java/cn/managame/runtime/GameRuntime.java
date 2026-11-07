package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.FrameworkErrorCodes;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import cn.managame.runtime.executor.RouteExecutor;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * The single entry for business logic. {@link #execute} runs a GameTask on its context's route: tasks with the
 * same domain and route key run one at a time in submission order, tasks of other keys or domains run
 * independently. Each domain has its own RouteExecutor, which decides the threads:
 *
 * <pre>{@code
 * GameRuntime runtime = GameRuntime.builder()
 *         .domain(PLAYER, RouteExecutors.virtualThreads())
 *         .domain(WORLD, RouteExecutors.striped(4))
 *         .build();
 * runtime.execute(GameContext.of(PLAYER, playerId, PLAYER_ID, playerId), context -> player.levelUp());
 * }</pre>
 *
 * {@link TaskInterceptor}s added with {@link Builder#intercept} run around every task, whichever entry gave it.
 */
public final class GameRuntime implements AutoCloseable {
    static final ScopedValue<Context> CURRENT = ScopedValue.newInstance();
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime");
    private final Map<Integer, RouteDomain> domains;
    private final BiConsumer<? super Context, ? super Throwable> errorHandler;
    private final TaskInterceptor[] interceptors;
    private volatile boolean shutdown;

    private GameRuntime(Builder b) {
        Map<Integer, RouteDomain> built = new HashMap<>();
        b.executors.forEach((domain, executor) -> built.put(domain, RouteDomain.of(domain, executor, b.resolvers.get(domain), b.metrics)));
        domains = Map.copyOf(built);
        errorHandler = b.errorHandler;
        interceptors = b.interceptors.toArray(TaskInterceptor[]::new);
        LOG.log(Level.INFO, () -> "Game runtime started; domains " + new TreeMap<>(domains)
                + (interceptors.length == 0 ? "" : "; " + interceptors.length + " task interceptors"));
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Where {@code message}, arriving from {@code source}, runs in {@code domain}: the domain's resolver gives the
     * route key, and the business identity is the source's, since that is who acts. Entries such as the
     * Dispatcher and the EventBus use this to build the context of a handler whose annotation names no routeKey. An
     * unregistered domain throws IllegalArgumentException; a resolver's own exception passes through.
     */
    public Route route(int domain, Context source, Object message) {
        RouteDomain target = domains.get(domain);
        if (target == null) throw new IllegalArgumentException("Unknown domain " + domain);
        return Route.of(domain, target.resolver().routeKey(source, message), source.businessType(), source.businessId());
    }

    /** IllegalArgumentException for an unregistered domain; entries call it as they register a method, so it fails at startup. */
    public void checkDomain(int domain) {
        if (!domains.containsKey(domain)) throw new IllegalArgumentException("Unknown domain " + domain);
    }

    /**
     * Queues {@code task} on the route of {@code context} and returns; the task later runs with the context as
     * {@link Context#current()}. It is also how work comes back from another thread or route, such as an RPC callback
     * or a visit to another player's route: execute with the context to continue on, the task's own or its request.
     * Throws GameRuntimeException with RUNTIME_CLOSED after shutdown (ROUTE_EXECUTOR_CLOSED when the domain's executor
     * refuses a task given while it shuts down) or ROUTE_EXECUTOR_OVERLOADED when the domain's executor is full; the
     * task then never runs, its {@link GameTask#refused} does, and a RequestContext is first failed with that code, so a
     * request is answered whether its task was accepted or not. A request whose task throws is failed with
     * RUNTIME_EXECUTION_ERROR. An unregistered domain throws IllegalArgumentException.
     * <p>
     * Refusing is for what comes in from outside. Called from a game task, this takes the work past every limit on load
     * and during shutdown, so that what was accepted finishes what it began, such as the second step of a trade. Any
     * other caller is an entry, an RPC callback too, as a task's context does not travel with it.
     * <p>
     * The runtime counts no task in flight: each domain's executor knows its own work, and shutdown waits for them to
     * have none. Close the entries that hand it work (servers, RPC, timers and the like) first, so nothing races with
     * shutdown.
     */
    public <C extends Context> void execute(C context, GameTask<? super C> task) {
        Objects.requireNonNull(task, "task");
        try {
            submit(context, () -> run(context, task));
        } catch (RuntimeException notAccepted) {
            task.refused(context);
            throw notAccepted;
        }
    }

    private void submit(Context context, Runnable work) {
        Objects.requireNonNull(context, "context");
        RouteDomain domain = domains.get(context.domain());
        if (domain == null) throw new IllegalArgumentException("Unknown domain " + context.domain());
        RouteExecutor executor = domain.executor();
        try {
            if (shutdown && !CURRENT.isBound()) throw new GameRuntimeException(RUNTIME_CLOSED, "the runtime is shut down");
            try {
                executor.execute(context.routeKey(), work);
            } catch (GameRuntimeException full) {
                if (!CURRENT.isBound()) throw full;
                executor.handOn(context.routeKey(), work);
            }
        } catch (GameRuntimeException refused) {
            throw refuse(domain, context, refused);
        }
    }

    /** Counts the refusal and fails a refused request with its code, which is then thrown. */
    private static GameRuntimeException refuse(RouteDomain domain, Context context, GameRuntimeException refusal) {
        domain.refused(refusal.errorCode());
        LOG.log(Level.DEBUG, () -> "Refused a task of domain " + context.domain() + ", route key " + context.routeKey() + ": "
                + FrameworkErrorCodes.describe(refusal.errorCode()));
        if (context instanceof RequestContext request) {
            try {
                request.fail(refusal.errorCode());
            } catch (RuntimeException e) {
                refusal.addSuppressed(e);
            }
        }
        return refusal;
    }

    private <C extends Context> void run(C context, GameTask<? super C> task) {
        try {
            ScopedValue.where(CURRENT, context).call(() -> {
                if (interceptors.length == 0) task.run(context);
                else intercept(context, task);
                return null;
            });
        } catch (Throwable cause) {
            domains.get(context.domain()).failed().increment();
            // An accepted request is always answered; fail does nothing if the task had already answered it.
            if (context instanceof RequestContext request) {
                try {
                    request.fail(RUNTIME_EXECUTION_ERROR);
                } catch (Throwable error) {
                    cause.addSuppressed(error);
                }
            }
            try {
                errorHandler.accept(context, cause);
            } catch (Throwable error) {
                error.addSuppressed(cause);
                LOG.log(Level.ERROR, "Game task error handler failed", error);
            }
        }
    }

    /**
     * The befores in order, the task unless one refused it, then the after of each before that returned true, in reverse,
     * and last the refused of a task that did not run. All of them run whatever one throws.
     */
    private <C extends Context> void intercept(C context, GameTask<? super C> task) throws Exception {
        int entered = 0;
        Throwable failure = null;
        try {
            while (entered < interceptors.length && interceptors[entered].before(context)) entered++;
            if (entered == interceptors.length) task.run(context);
            else refused(context);
        } catch (Exception | Error e) {
            failure = e;
        }
        Throwable thrown = failure;
        for (int i = entered - 1; i >= 0; i--) {
            try {
                interceptors[i].after(context, failure);
            } catch (Exception | Error e) {
                thrown = joined(thrown, e);
            }
        }
        if (entered < interceptors.length) {
            try {
                task.refused(context);
            } catch (Exception | Error e) {
                thrown = joined(thrown, e);
            }
        }
        if (thrown instanceof Error error) throw error;
        if (thrown != null) throw (Exception) thrown;
    }

    private static Throwable joined(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    /** An interceptor refused the task; fail does nothing if it answered the request, as it should. */
    private static void refused(Context context) {
        LOG.log(Level.DEBUG, () -> "An interceptor refused a task of domain " + context.domain() + ", route key " + context.routeKey());
        if (context instanceof RequestContext request) request.fail(RUNTIME_EXECUTION_ERROR);
    }

    /**
     * Refuses tasks from outside; accepted ones still run and may hand work on, and the executors shut down once none is
     * left. Returns at once. Idempotent.
     */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        LOG.log(Level.INFO, "Game runtime shutting down; accepted tasks still run");
        Thread.ofVirtual().name("game-runtime-shutdown").start(this::shutDownExecutorsWhenIdle);
    }

    /**
     * A task hands on before it ends, so work moving between domains always shows in one, yet a look at one domain after
     * another can miss it once. Looking twice in a row cannot: that would take two whole hops between two looks.
     */
    private void shutDownExecutorsWhenIdle() {
        try {
            while (!idle() || !idle()) Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        domains.values().forEach(domain -> domain.executor().shutdown());
    }

    private boolean idle() {
        for (RouteDomain domain : domains.values()) if (domain.executor().pending() > 0) return false;
        return true;
    }

    public boolean isShutdown() { return shutdown; }

    /** Whether shut down with every accepted task finished. */
    public boolean isTerminated() {
        try {
            return awaitTermination(Duration.ZERO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Waits for accepted tasks after shutdown, each domain's executor finishing its own; false when the timeout elapsed
     * first, or before shutdown.
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!shutdown) return false;
        for (RouteDomain domain : domains.values())
            if (!domain.executor().awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return false;
        return true;
    }

    /**
     * Shuts down and waits for every accepted task to finish. Must not be called from a game task, which would
     * wait for itself.
     */
    @Override public void close() {
        if (CURRENT.isBound()) throw new IllegalStateException("Cannot close the runtime from one of its tasks");
        shutdown();
        boolean interrupted = false;
        for (RouteDomain domain : domains.values()) {
            boolean terminated = false;
            while (!terminated) {
                try {
                    terminated = domain.executor().awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        domains.values().forEach(domain -> domain.gauges().forEach(Meter::close));
        LOG.log(Level.INFO, "Game runtime closed; every accepted task finished");
    }

    /** What the runtime keeps per domain: who runs its tasks, how a message finds its key, and its meters. */
    private record RouteDomain(RouteExecutor executor, RouteKeyResolver resolver, Counter failed, Map<Integer, Counter> refusals,
                               List<Meter> gauges) {
        private static final String REFUSED = "managame.runtime.tasks.refused";

        static RouteDomain of(int domain, RouteExecutor executor, RouteKeyResolver resolver, Metrics metrics) {
            String id = String.valueOf(domain);
            return new RouteDomain(executor, resolver, metrics.counter("managame.runtime.tasks.failed", "domain", id),
                    Map.of(RUNTIME_CLOSED, metrics.counter(REFUSED, "domain", id, "code", "RUNTIME_CLOSED"),
                            ROUTE_EXECUTOR_OVERLOADED, metrics.counter(REFUSED, "domain", id, "code", "ROUTE_EXECUTOR_OVERLOADED"),
                            ROUTE_EXECUTOR_CLOSED, metrics.counter(REFUSED, "domain", id, "code", "ROUTE_EXECUTOR_CLOSED")),
                    List.of(metrics.gauge("managame.runtime.tasks.pending", executor::pending, "domain", id),
                            metrics.gauge("managame.runtime.routes.max_pending", executor::maxPending, "domain", id),
                            metrics.gauge("managame.runtime.routes.active", executor::routes, "domain", id)));
        }

        void refused(int errorCode) {
            Counter counter = refusals.get(errorCode);
            if (counter != null) counter.increment();
        }

        @Override public String toString() { return executor.toString(); }
    }

    public static final class Builder {
        private final Map<Integer, RouteExecutor> executors = new HashMap<>();
        private final Map<Integer, RouteKeyResolver> resolvers = new HashMap<>();
        private final Map<RouteExecutor, Integer> owners = new IdentityHashMap<>();
        private final List<TaskInterceptor> interceptors = new ArrayList<>();
        private Metrics metrics = Metrics.noop();
        private BiConsumer<? super Context, ? super Throwable> errorHandler = (context, cause) ->
                LOG.log(Level.ERROR, "Game task failed; domain=" + context.domain() + ", routeKey=" + context.routeKey()
                        + ", businessType=" + context.businessType() + ", businessId=" + context.businessId(), cause);

        private Builder() {}

        /**
         * Registers a domain routed by the source's business id, the acting player's, which suits most domains; see
         * {@link #domain(int, RouteExecutor, RouteKeyResolver)}.
         */
        public Builder domain(int domain, RouteExecutor executor) {
            return domain(domain, executor, RouteKeys.businessId());
        }

        /**
         * Registers a domain, the executor that runs its tasks, and how a message arriving for it finds its route
         * key, which the application decides per domain: a player domain may take the player's id from the source
         * context, a guild domain the guild id from the message. An executor serves one domain only.
         */
        public Builder domain(int domain, RouteExecutor executor, RouteKeyResolver resolver) {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(resolver, "resolver");
            if (executors.containsKey(domain)) throw new IllegalArgumentException("Duplicate domain " + domain);
            Integer owner = owners.putIfAbsent(executor, domain);
            if (owner != null) throw new IllegalArgumentException("The executor already serves domain " + owner);
            executors.put(domain, executor);
            resolvers.put(domain, resolver);
            return this;
        }

        /**
         * Adds interceptors around every task, whichever entry gave it; their before methods run in the order they were
         * added. See {@link TaskInterceptor}.
         */
        public Builder intercept(Collection<? extends TaskInterceptor> interceptors) {
            for (TaskInterceptor interceptor : interceptors) this.interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * Receives what a task throws; by default it is logged. Runs on the task's thread.
         */
        public Builder errorHandler(BiConsumer<? super Context, ? super Throwable> handler) {
            errorHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Where the runtime reports, by domain: tasks refused, by error code, tasks that threw, and what its executor
         * holds, read when the backend collects; {@link Metrics#noop()} by default. A task that runs and returns reports
         * nothing, so metrics cost it nothing.
         */
        public Builder metrics(Metrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        public GameRuntime build() {
            if (executors.isEmpty()) throw new IllegalStateException("At least one domain is required");
            return new GameRuntime(this);
        }
    }
}
