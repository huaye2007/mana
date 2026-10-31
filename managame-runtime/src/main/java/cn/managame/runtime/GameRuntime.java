package cn.managame.runtime;

import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.runtime.executor.RouteExecutor;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 */
public final class GameRuntime implements AutoCloseable {
    static final ScopedValue<Context> CURRENT = ScopedValue.newInstance();
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime");
    private final Map<Integer, RouteExecutor> executors;
    private final Map<Integer, RouteKeyResolver> resolvers;
    private final BiConsumer<? super Context, ? super Throwable> errorHandler;
    /** Accepted tasks that have not finished; termination waits for zero. */
    private final AtomicLong pending = new AtomicLong();
    private final AtomicBoolean terminating = new AtomicBoolean();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile boolean shutdown;

    private GameRuntime(Builder b) {
        executors = Map.copyOf(b.executors);
        resolvers = Map.copyOf(b.resolvers);
        errorHandler = b.errorHandler;
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Where {@code message}, arriving from {@code source}, runs in {@code domain}: the domain's resolver gives the
     * route key, and the business identity is the source's, since that is who acts. Entries such as the
     * Dispatcher and the EventBus use this to build the context of a handler. An unregistered domain throws
     * IllegalArgumentException; a resolver's own exception passes through.
     */
    public Route route(int domain, Context source, Object message) {
        RouteKeyResolver resolver = resolvers.get(domain);
        if (resolver == null) throw new IllegalArgumentException("Unknown domain " + domain);
        return Route.of(domain, resolver.routeKey(source, message), source.businessType(), source.businessId());
    }

    /**
     * Queues {@code task} on the route of {@code context} and returns; the task later runs with the context as
     * {@link Context#current()}. Throws GameRuntimeException with RUNTIME_CLOSED after shutdown or
     * ROUTE_EXECUTOR_OVERLOADED when the domain's executor is full; the task then never runs. An unregistered
     * domain throws IllegalArgumentException.
     */
    public <C extends Context> void execute(C context, GameTask<? super C> task) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(task, "task");
        RouteExecutor executor = executors.get(context.domain());
        if (executor == null) throw new IllegalArgumentException("Unknown domain " + context.domain());
        // Counted before the shutdown check, so shutdown never misses a task that is being accepted.
        pending.incrementAndGet();
        if (shutdown) {
            finished();
            throw new GameRuntimeException(RUNTIME_CLOSED, "the runtime is shut down");
        }
        try {
            executor.execute(context.routeKey(), () -> run(context, task));
        } catch (Throwable refused) {
            finished();
            throw refused;
        }
    }

    private <C extends Context> void run(C context, GameTask<? super C> task) {
        try {
            ScopedValue.where(CURRENT, context).call(() -> {
                task.run(context);
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
        } finally {
            finished();
        }
    }

    private void finished() {
        if (pending.decrementAndGet() == 0 && shutdown) terminate();
    }

    /** Refuses new tasks; accepted ones still run. Idempotent. */
    public void shutdown() {
        shutdown = true;
        if (pending.get() == 0) terminate();
    }

    private void terminate() {
        if (!terminating.compareAndSet(false, true)) return;
        executors.values().forEach(RouteExecutor::close);
        terminated.countDown();
    }

    public boolean isShutdown() { return shutdown; }

    /** Whether shut down with every accepted task finished. */
    public boolean isTerminated() { return terminated.getCount() == 0; }

    /** Waits for accepted tasks after shutdown; false when the timeout elapsed first. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return terminated.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Shuts down and waits for every accepted task to finish. Must not be called from a game task, which would
     * wait for itself.
     */
    @Override public void close() {
        if (CURRENT.isBound()) throw new IllegalStateException("Cannot close the runtime from one of its tasks");
        shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                terminated.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static final class Builder {
        private final Map<Integer, RouteExecutor> executors = new HashMap<>();
        private final Map<Integer, RouteKeyResolver> resolvers = new HashMap<>();
        private final Map<RouteExecutor, Integer> owners = new IdentityHashMap<>();
        private BiConsumer<? super Context, ? super Throwable> errorHandler = (context, cause) ->
                LOG.log(Level.ERROR, "Game task failed; domain=" + context.domain() + ", routeKey=" + context.routeKey()
                        + ", businessType=" + context.businessType() + ", businessId=" + context.businessId(), cause);

        private Builder() {}

        /**
         * Registers a domain whose route keys come from the messages' {@code @RouteKey} fields or methods for
         * it; see {@link #domain(int, RouteExecutor, RouteKeyResolver)}.
         */
        public Builder domain(int domain, RouteExecutor executor) {
            return domain(domain, executor, RouteKeys.annotated(domain));
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

        /** Receives what a task throws; by default it is logged. Runs on the task's thread. */
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
