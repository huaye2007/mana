package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_FAILED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_PROTOCOL_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;

import cn.managame.Metadata;
import cn.managame.metrics.Counter;
import cn.managame.metrics.LatencyBuckets;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Outbound client for calling external AI service processes with per-lane bulkhead isolation.
 * Each named lane owns an independent concurrency gate, bounded queue and metrics, preventing long-running
 * streaming or reasoning requests from starving latency-sensitive decision calls. Supports typed request-response
 * ({@link #call}, blocking or non-blocking via {@link AiTransport#callAsync}) and typed streaming ({@link #stream})
 * with independent idle and total timeouts. Memory, prompts, model routing and third-party AI SDKs stay in the
 * external AI service processes. Close after stopping submissions and before closing the game runtime.
 */
public final class AiClient implements AutoCloseable {
    public static final String DEFAULT_LANE = "default";
    public static final int DEFAULT_WORKERS = 8;
    public static final int DEFAULT_CAPACITY = 64;
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final System.Logger LOG = System.getLogger("cn.managame.ai");

    private final AiTransport transport;
    private final AiJson json;
    private final Map<String, Lane> lanes;
    private final ConcurrentHashMap<Class<?>, Optional<String>> responseSchemas = new ConcurrentHashMap<>();
    private final Set<Thread> activeWorkers = ConcurrentHashMap.newKeySet();
    private final Set<CallTask<?>> activeCalls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object drainLock = new Object();
    private final long shutdownTimeoutNanos;
    private final boolean observing;
    private volatile boolean closed;
    private volatile boolean forceStopped;

    private AiClient(Builder builder) {
        transport = builder.transport;
        json = builder.json;
        shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();
        observing = builder.metrics != Metrics.noop();
        Map<String, Lane> built = new LinkedHashMap<>();
        builder.lanes.forEach((name, spec) -> built.put(name, new Lane(name, spec.workers(), spec.capacity(), builder.metrics)));
        lanes = Map.copyOf(built);
        LOG.log(Level.INFO, "AI client started with lanes {0}", lanes.keySet());
    }

    public static Builder builder(AiTransport transport) { return new Builder(transport); }

    /** Call an external AI operation on the default lane with empty metadata. */
    public <T> CompletableFuture<T> call(String operation, Object request, Class<T> response, Duration timeout) {
        return call(DEFAULT_LANE, operation, Metadata.empty(), request, response, timeout);
    }

    /** Call an external AI operation on the default lane with {@code metadata}. */
    public <T> CompletableFuture<T> call(String operation, Metadata metadata, Object request, Class<T> response, Duration timeout) {
        return call(DEFAULT_LANE, operation, metadata, request, response, timeout);
    }

    /**
     * Call an external AI operation on {@code lane}. Completes once with the decoded result or an {@link AiException}
     * carrying one of {@link cn.managame.FrameworkErrorCodes} ({@code AI_CLOSED}, {@code AI_OVERLOADED},
     * {@code AI_TIMEOUT}, {@code AI_PROTOCOL_ERROR}, {@code AI_FAILED}), or cancellation. Timeout covers queueing,
     * encoding, transport I/O and decoding.
     */
    public <T> CompletableFuture<T> call(String lane, String operation, Metadata metadata, Object request,
                                         Class<T> response, Duration timeout) {
        Lane target = requireLane(lane);
        validateCommon(operation, metadata, request, response);
        if (timeout == null || !timeout.isPositive()) throw new IllegalArgumentException("Positive timeout required");
        return submit(new CallTask<>(target, operation, metadata, request, response, null, 0L, timeout.toNanos()));
    }

    /** Stream typed chunks on the default lane with empty metadata. */
    public <T> CompletableFuture<Void> stream(String operation, Object request, Class<T> chunkType,
                                              Duration idleTimeout, Duration totalTimeout, Consumer<? super T> onChunk) {
        return stream(DEFAULT_LANE, operation, Metadata.empty(), request, chunkType, idleTimeout, totalTimeout, onChunk);
    }

    /**
     * Stream typed chunks on {@code lane}. Fails with {@code AI_TIMEOUT} if no chunk arrives for {@code idleTimeout}
     * (including initial stall) or when {@code totalTimeout} elapses. Calling {@code cancel(true)} on the returned
     * future interrupts the in-flight stream immediately.
     */
    public <T> CompletableFuture<Void> stream(String lane, String operation, Metadata metadata, Object request,
                                              Class<T> chunkType, Duration idleTimeout, Duration totalTimeout,
                                              Consumer<? super T> onChunk) {
        Lane target = requireLane(lane);
        validateCommon(operation, metadata, request, chunkType);
        Objects.requireNonNull(onChunk, "onChunk");
        if (idleTimeout == null || !idleTimeout.isPositive() || totalTimeout == null || !totalTimeout.isPositive())
            throw new IllegalArgumentException("Positive idleTimeout and totalTimeout required");
        long totalNanos = totalTimeout.toNanos();
        long idleNanos = Math.min(idleTimeout.toNanos(), totalNanos);
        return submit(new CallTask<>(target, operation, metadata, request, chunkType, onChunk, idleNanos, totalNanos));
    }

    private Lane requireLane(String name) {
        Lane lane = lanes.get(Objects.requireNonNull(name, "lane"));
        if (lane == null) throw new IllegalArgumentException("Unknown AI lane: " + name);
        return lane;
    }

    private static void validateCommon(String operation, Metadata metadata, Object request, Class<?> type) {
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("Non-blank operation required");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(type, "response");
    }

    private String responseSchema(Class<?> type) {
        return responseSchemas.computeIfAbsent(type, t -> Optional.ofNullable(json.outputSchema(t))).orElse(null);
    }

    private <R> CompletableFuture<R> submit(CallTask<R> task) {
        Lane lane = task.lane;
        if (closed) {
            task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient is closed"));
            return task.exposed;
        }
        activeCalls.add(task);
        task.inner.orTimeout(task.totalNanos, TimeUnit.NANOSECONDS);
        if (task.idleNanos > 0 && task.idleNanos < task.totalNanos) {
            task.armIdleWatch(task.idleNanos);
        }
        if (lane.permits.tryAcquire()) {
            if (closed) {
                lane.permits.release();
                task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient is closed"));
                return task.exposed;
            }
            startWorker(lane, task);
            return task.exposed;
        }
        if (!lane.queue.offer(task)) {
            task.inner.completeExceptionally(new AiException(AI_OVERLOADED, "AI lane '" + lane.name + "' queue is full"));
            return task.exposed;
        }
        if (closed && lane.queue.remove(task)) {
            task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient is closed"));
            return task.exposed;
        }
        maybeStartWorker(lane);
        return task.exposed;
    }

    private void maybeStartWorker(Lane lane) {
        if (!forceStopped && !lane.queue.isEmpty() && lane.permits.tryAcquire()) {
            CallTask<?> next = lane.queue.poll();
            if (next == null) {
                lane.permits.release();
                return;
            }
            startWorker(lane, next);
        }
    }

    private void startWorker(Lane lane, CallTask<?> first) {
        inFlight.incrementAndGet();
        try {
            lane.threads.newThread(() -> runWorker(lane, first)).start();
        } catch (Throwable failure) {
            lane.permits.release();
            first.inner.completeExceptionally(new AiException(AI_FAILED, "Cannot start AI worker", failure));
            finishFlight();
        }
    }

    private void runWorker(Lane lane, CallTask<?> first) {
        Thread self = Thread.currentThread();
        activeWorkers.add(self);
        boolean handedOff = false;
        try {
            CallTask<?> task = first;
            while (task != null && !forceStopped) {
                if (!task.inner.isDone()) {
                    handedOff = execute(task, self);
                    if (handedOff) return;
                }
                Thread.interrupted();
                task = lane.queue.poll();
            }
        } finally {
            activeWorkers.remove(self);
            if (!handedOff) {
                lane.permits.release();
                if (!forceStopped && !lane.queue.isEmpty()) maybeStartWorker(lane);
                finishFlight();
            }
        }
    }

    /** Returns true when lane permit and inFlight ownership moved to an async transport stage. */
    private <R> boolean execute(CallTask<R> task, Thread self) {
        long remaining = task.remainingNanos();
        if (remaining <= 0) {
            task.failTimeout("AI call timed out: " + task.operation);
            return false;
        }
        String payload;
        try {
            payload = json.encode(task.request);
        } catch (Throwable failure) {
            task.inner.completeExceptionally(new AiException(AI_PROTOCOL_ERROR, "Cannot encode AI request for " + task.operation, failure));
            return false;
        }
        remaining = task.remainingNanos();
        if (remaining <= 0) {
            task.failTimeout("AI call timed out: " + task.operation);
            return false;
        }
        AiRequest wireRequest = new AiRequest(task.lane.name, task.operation, task.metadata, payload, responseSchema(task.responseType));
        if (task.isStream()) {
            executeStream(task, wireRequest, remaining, self);
            return false;
        }
        CompletionStage<String> stage;
        try {
            stage = transport.callAsync(wireRequest, Duration.ofNanos(remaining));
        } catch (Throwable failure) {
            task.inner.completeExceptionally(new AiException(AI_FAILED, "AI transport failed for " + task.operation, failure));
            return false;
        }
        if (stage != null) {
            task.asyncStage = stage;
            if (task.inner.isDone() && stage instanceof Future<?> f) f.cancel(true);
            Lane lane = task.lane;
            stage.whenComplete((raw, error) -> {
                try {
                    completeCall(task, raw, error);
                } finally {
                    lane.permits.release();
                    if (!forceStopped && !lane.queue.isEmpty()) maybeStartWorker(lane);
                    finishFlight();
                }
            });
            return true;
        }
        task.runningThread = self;
        try {
            String raw = transport.call(wireRequest, Duration.ofNanos(remaining));
            completeCall(task, raw, null);
        } catch (Throwable failure) {
            completeCall(task, null, failure);
        } finally {
            task.runningThread = null;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <R> void completeCall(CallTask<R> task, String raw, Throwable error) {
        if (task.inner.isDone()) return;
        if (error != null) {
            Throwable cause = unwrap(error);
            if (cause instanceof AiException ai) task.inner.completeExceptionally(ai);
            else if (cause instanceof TimeoutException) task.failTimeout("AI call timed out: " + task.operation);
            else task.inner.completeExceptionally(new AiException(AI_FAILED, "AI transport failed for " + task.operation, cause));
            return;
        }
        if (task.remainingNanos() <= 0) {
            task.failTimeout("AI call timed out: " + task.operation);
            return;
        }
        try {
            R value = (R) json.decode(raw, task.responseType);
            if (task.remainingNanos() <= 0) task.failTimeout("AI call timed out: " + task.operation);
            else task.inner.complete(value);
        } catch (Throwable failure) {
            task.inner.completeExceptionally(new AiException(AI_PROTOCOL_ERROR, "Cannot decode AI response for " + task.operation, failure));
        }
    }

    @SuppressWarnings("unchecked")
    private <R> void executeStream(CallTask<R> task, AiRequest wireRequest, long remaining, Thread self) {
        task.runningThread = self;
        try {
            Consumer<Object> sink = (Consumer<Object>) task.onChunk;
            transport.stream(wireRequest, Duration.ofNanos(remaining), rawChunk -> {
                if (task.inner.isDone() || Thread.currentThread().isInterrupted())
                    throw new AiException(AI_TIMEOUT, "AI stream stopped: " + task.operation);
                if (task.remainingNanos() <= 0)
                    throw new AiException(AI_TIMEOUT, "AI stream timed out: " + task.operation);
                task.lastActivityNanos = System.nanoTime();
                Object chunk;
                try {
                    chunk = json.decode(rawChunk, task.responseType);
                } catch (Throwable failure) {
                    throw new AiException(AI_PROTOCOL_ERROR, "Cannot decode AI stream chunk for " + task.operation, failure);
                }
                sink.accept(chunk);
            });
            if (task.remainingNanos() <= 0) task.failTimeout("AI stream timed out: " + task.operation);
            else task.inner.complete(null);
        } catch (Throwable failure) {
            if (!task.inner.isDone()) {
                Throwable cause = unwrap(failure);
                if (cause instanceof AiException ai) task.inner.completeExceptionally(ai);
                else if (cause instanceof TimeoutException) task.failTimeout("AI stream timed out: " + task.operation);
                else task.inner.completeExceptionally(new AiException(AI_FAILED, "AI stream failed for " + task.operation, cause));
            }
        } finally {
            task.runningThread = null;
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : error;
    }

    private void finishFlight() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized (drainLock) { drainLock.notifyAll(); }
        }
    }

    private boolean queuesEmpty() {
        for (Lane lane : lanes.values()) if (!lane.queue.isEmpty()) return false;
        return true;
    }

    /**
     * Refuses new calls and streams, then waits up to {@code shutdownTimeout} for admitted work to finish.
     * Any work still running or queued past the timeout is interrupted and failed with {@code AI_CLOSED}.
     * Idempotent. Call before closing the game runtime.
     */
    @Override public void close() {
        synchronized (drainLock) {
            if (closed) return;
            closed = true;
        }
        lanes.values().forEach(this::maybeStartWorker);
        long deadline = System.nanoTime() + shutdownTimeoutNanos;
        boolean interrupted = false;
        synchronized (drainLock) {
            while (inFlight.get() > 0 || !queuesEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    drainLock.wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
            }
        }
        forceStopped = true;
        for (Thread worker : activeWorkers) worker.interrupt();
        for (CallTask<?> task : activeCalls) {
            task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient closed before completion"));
        }
        lanes.values().forEach(lane -> {
            lane.queue.clear();
            lane.meters.forEach(Meter::close);
        });
        if (interrupted) Thread.currentThread().interrupt();
        LOG.log(Level.INFO, "AI client closed");
    }

    private static final class Lane {
        final String name;
        final ArrayBlockingQueue<CallTask<?>> queue;
        final Semaphore permits;
        final ThreadFactory threads;
        final Counter succeeded, refused, failed;
        final LatencyBuckets latency = new LatencyBuckets();
        final List<Meter> meters;

        Lane(String name, int workers, int capacity, Metrics metrics) {
            this.name = name;
            queue = new ArrayBlockingQueue<>(capacity);
            permits = new Semaphore(workers);
            threads = Thread.ofVirtual().name("game-ai-" + name + "-", 0).factory();
            succeeded = metrics.counter("managame.ai.client.succeeded", "lane", name);
            refused = metrics.counter("managame.ai.client.refused", "lane", name);
            failed = metrics.counter("managame.ai.client.failed", "lane", name);
            List<Meter> registered = new ArrayList<>();
            registered.add(metrics.gauge("managame.ai.client.queued", queue::size, "lane", name));
            registered.addAll(latency.report(metrics, "managame.ai.client.latency", "lane", name));
            meters = List.copyOf(registered);
        }
    }

    private final class CallTask<R> {
        final Lane lane;
        final String operation;
        final Metadata metadata;
        final Object request;
        final Class<?> responseType;
        final Consumer<?> onChunk;
        final long startedNanos;
        final long idleNanos;
        final long totalNanos;
        final CompletableFuture<R> inner = new CompletableFuture<>();
        final CompletableFuture<R> exposed = new CompletableFuture<>();
        final AtomicBoolean counted = new AtomicBoolean();
        volatile long lastActivityNanos;
        volatile CompletableFuture<Void> idleWatch;
        volatile Thread runningThread;
        volatile CompletionStage<?> asyncStage;

        CallTask(Lane lane, String operation, Metadata metadata, Object request, Class<?> responseType,
                 Consumer<?> onChunk, long idleNanos, long totalNanos) {
            this.lane = lane;
            this.operation = operation;
            this.metadata = metadata;
            this.request = request;
            this.responseType = responseType;
            this.onChunk = onChunk;
            this.startedNanos = System.nanoTime();
            this.lastActivityNanos = startedNanos;
            this.idleNanos = idleNanos;
            this.totalNanos = totalNanos;
            inner.whenComplete(this::onInnerComplete);
            exposed.whenComplete((value, failure) -> {
                if (exposed.isCancelled()) inner.cancel(true);
            });
        }

        boolean isStream() { return onChunk != null; }

        long remainingNanos() { return totalNanos - (System.nanoTime() - startedNanos); }

        void failTimeout(String message) {
            inner.completeExceptionally(new AiException(AI_TIMEOUT, message));
        }

        void armIdleWatch(long delayNanos) {
            if (inner.isDone()) return;
            long capped = Math.min(delayNanos, remainingNanos());
            if (capped <= 0) {
                failTimeout("AI stream timed out: " + operation);
                return;
            }
            CompletableFuture<Void> watch = new CompletableFuture<>();
            CompletableFuture<Void> prev = idleWatch;
            idleWatch = watch;
            if (prev != null) prev.complete(null);
            if (inner.isDone()) {
                watch.complete(null);
                return;
            }
            watch.orTimeout(capped, TimeUnit.NANOSECONDS).whenComplete((v, err) -> {
                if (!(err instanceof TimeoutException) || inner.isDone()) return;
                long idle = System.nanoTime() - lastActivityNanos;
                if (idle >= idleNanos) {
                    failTimeout("AI stream idle timed out: " + operation);
                } else {
                    armIdleWatch(idleNanos - idle);
                }
            });
        }

        private void onInnerComplete(R value, Throwable failure) {
            activeCalls.remove(this);
            CompletableFuture<Void> watch = idleWatch;
            if (watch != null) watch.complete(null);
            Throwable translated = failure;
            if (failure instanceof TimeoutException) {
                translated = new AiException(AI_TIMEOUT, (isStream() ? "AI stream timed out: " : "AI call timed out: ") + operation);
            }
            if (translated == null) {
                exposed.complete(value);
            } else if (inner.isCancelled()) {
                exposed.cancel(true);
            } else {
                exposed.completeExceptionally(translated);
            }
            if (counted.compareAndSet(false, true)) {
                if (observing) lane.latency.record(Math.max(0L, System.nanoTime() - startedNanos));
                if (translated == null) {
                    lane.succeeded.increment();
                } else if (translated instanceof AiException ai
                        && (ai.errorCode() == AI_OVERLOADED || ai.errorCode() == AI_CLOSED)) {
                    lane.refused.increment();
                } else {
                    lane.failed.increment();
                }
            }
            if (translated != null) {
                lane.queue.remove(this);
                Thread thread = runningThread;
                if (thread != null) thread.interrupt();
                CompletionStage<?> stage = asyncStage;
                if (stage instanceof Future<?> future) future.cancel(true);
            }
        }
    }

    public static final class Builder {
        private record LaneSpec(int workers, int capacity) {}

        private final AiTransport transport;
        private final Map<String, LaneSpec> lanes = new LinkedHashMap<>();
        private AiJson json = AiJson.jackson();
        private Metrics metrics = Metrics.noop();
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

        private Builder(AiTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            lanes.put(DEFAULT_LANE, new LaneSpec(DEFAULT_WORKERS, DEFAULT_CAPACITY));
        }

        /** Configure the default lane's concurrency and queue capacity. */
        public Builder defaultLane(int workers, int capacity) { return lane(DEFAULT_LANE, workers, capacity); }

        /**
         * Define or override an isolated bulkhead lane (for example {@code "decision"}, {@code "reasoning"}, {@code "stream"}).
         * Lane names appear in metric tags and must be lowercase identifiers.
         */
        public Builder lane(String name, int workers, int capacity) {
            if (name == null || !name.matches("[a-z0-9_-]{1,32}"))
                throw new IllegalArgumentException("Invalid lane name: " + name);
            if (workers < 1 || capacity < 1) throw new IllegalArgumentException("Positive lane limits required");
            lanes.put(name, new LaneSpec(workers, capacity));
            return this;
        }

        public Builder json(AiJson json) { this.json = Objects.requireNonNull(json, "json"); return this; }

        public Builder metrics(Metrics metrics) { this.metrics = Objects.requireNonNull(metrics, "metrics"); return this; }

        public Builder shutdownTimeout(Duration timeout) {
            if (timeout == null || !timeout.isPositive())
                throw new IllegalArgumentException("Positive shutdown timeout required");
            shutdownTimeout = timeout;
            return this;
        }

        public AiClient build() { return new AiClient(this); }
    }
}
