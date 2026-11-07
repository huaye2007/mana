package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.FrameworkErrorCodes;

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
    private final Map<Integer, RouteExecutor> executors;
    private final Map<Integer, RouteKeyResolver> resolvers;
    private final BiConsumer<? super Context, ? super Throwable> errorHandler;
    private final TaskInterceptor[] interceptors;
    private volatile boolean shutdown;

    private GameRuntime(Builder b) {
        executors = Map.copyOf(b.executors);
        resolvers = Map.copyOf(b.resolvers);
        errorHandler = b.errorHandler;
        interceptors = b.interceptors.toArray(TaskInterceptor[]::new);
        LOG.log(Level.INFO, () -> "Game runtime started; domains " + new TreeMap<>(executors)
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
        RouteKeyResolver resolver = resolvers.get(domain);
        if (resolver == null) throw new IllegalArgumentException("Unknown domain " + domain);
        return Route.of(domain, resolver.routeKey(source, message), source.businessType(), source.businessId());
    }

    /** IllegalArgumentException for an unregistered domain; entries call it as they register a method, so it fails at startup. */
    public void checkDomain(int domain) {
        if (!resolvers.containsKey(domain)) throw new IllegalArgumentException("Unknown domain " + domain);
    }

    /**
     * Queues {@code task} on the route of {@code context} and returns; the task later runs with the context as
     * {@link Context#current()}. It is also how work comes back from another thread or route, such as an RPC callback
     * or a visit to another player's route: execute with the context to continue on, the task's own or its request.
     * Throws GameRuntimeException with RUNTIME_CLOSED after shutdown (ROUTE_EXECUTOR_CLOSED when the domain's executor
     * refuses a task given while it shuts down) or ROUTE_EXECUTOR_OVERLOADED when the domain's executor is full; the
     * task then never runs, and a RequestContext is first failed with that code, so a request is answered whether its
     * task was accepted or not. A request whose task throws is failed with RUNTIME_EXECUTION_ERROR. An unregistered
     * domain throws IllegalArgumentException.
     * <p>
     * The runtime counts no task in flight: each domain's executor knows its own work, and shutdown asks them to finish
     * it. Close the entries that hand it work (servers, timers and the like) first, so nothing races with shutdown.
     */
    public <C extends Context> void execute(C context, GameTask<? super C> task) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(task, "task");
        RouteExecutor executor = executors.get(context.domain());
        if (executor == null) throw new IllegalArgumentException("Unknown domain " + context.domain());
        if (shutdown) throw refuse(context, new GameRuntimeException(RUNTIME_CLOSED, "the runtime is shut down"));
        try {
            executor.execute(context.routeKey(), () -> run(context, task));
        } catch (GameRuntimeException refused) {
            throw refuse(context, refused);
        }
    }

    /** Fails a refused request with the refusal's code, which is then thrown. */
    private static GameRuntimeException refuse(Context context, GameRuntimeException refusal) {
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

    /** The befores in order, the task unless one refused it, then the after of each before that returned true, in reverse. */
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
                if (thrown == null) thrown = e;
                else thrown.addSuppressed(e);
            }
        }
        if (thrown instanceof Error error) throw error;
        if (thrown != null) throw (Exception) thrown;
    }

    /** An interceptor refused the task; fail does nothing if it answered the request, as it should. */
    private static void refused(Context context) {
        LOG.log(Level.DEBUG, () -> "An interceptor refused a task of domain " + context.domain() + ", route key " + context.routeKey());
        if (context instanceof RequestContext request) request.fail(RUNTIME_EXECUTION_ERROR);
    }

    /** Refuses new tasks; accepted ones still run. Idempotent. */
    public void shutdown() {
        boolean first = !shutdown;
        shutdown = true;
        executors.values().forEach(RouteExecutor::shutdown);
        if (first) LOG.log(Level.INFO, "Game runtime shutting down; accepted tasks still run");
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
        for (RouteExecutor executor : executors.values())
            if (!executor.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return false;
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
        for (RouteExecutor executor : executors.values()) {
            boolean terminated = false;
            while (!terminated) {
                try {
                    terminated = executor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        LOG.log(Level.INFO, "Game runtime closed; every accepted task finished");
    }

    public static final class Builder {
        private final Map<Integer, RouteExecutor> executors = new HashMap<>();
        private final Map<Integer, RouteKeyResolver> resolvers = new HashMap<>();
        private final Map<RouteExecutor, Integer> owners = new IdentityHashMap<>();
        private final List<TaskInterceptor> interceptors = new ArrayList<>();
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

        public GameRuntime build() {
            if (executors.isEmpty()) throw new IllegalStateException("At least one domain is required");
            return new GameRuntime(this);
        }
    }
}
