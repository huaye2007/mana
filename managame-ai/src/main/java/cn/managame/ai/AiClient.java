package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CIRCUIT_OPEN;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Outbound client for calling external AI service processes with per-lane bulkhead isolation, optional per-key
 * concurrency/session control ({@link KeyConflict}), and optional lane circuit breaking.
 * Supports typed request-response ({@link #call}, blocking or non-blocking via {@link AiTransport#callAsync}) and
 * typed streaming ({@link #stream}, blocking or non-blocking via {@link AiTransport#streamAsync}) with independent
 * idle and total timeouts and TTFT latency buckets sized for external AI workloads (50ms to 30s).
 * Memory, prompts, model routing and third-party AI SDKs stay in the external AI service processes.
 * Close after stopping submissions and before closing the game runtime.
 */
public final class AiClient implements AutoCloseable {
    public static final String DEFAULT_LANE = "default";
    public static final int DEFAULT_WORKERS = 8;
    public static final int DEFAULT_CAPACITY = 64;
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final long[] AI_LATENCY_BOUNDS_MS = {50, 100, 250, 500, 1000, 2000, 5000, 10000, 30000};
    private static final System.Logger LOG = System.getLogger("cn.managame.ai");

    /** How a lane handles concurrent invocations sharing the same non-null {@code routingKey}. */
    public enum KeyConflict {
        /** Reject the new invocation with {@code AI_OVERLOADED} once the key hits its per-key limit. */
        REJECT,
        /** Cancel the oldest in-flight invocation for the same key and admit the new one (e.g. interrupting NPC chat). */
        CANCEL_PREVIOUS
    }

    /** Per-invocation routing, correlation and metadata options. */
    public record CallOptions(String lane, String routingKey, String requestId, Metadata metadata) {
        public CallOptions {
            if (lane == null || lane.isBlank()) throw new IllegalArgumentException("Empty lane");
            Objects.requireNonNull(metadata, "metadata");
        }

        public static CallOptions of(String lane) { return new CallOptions(lane, null, null, Metadata.empty()); }
        public static CallOptions defaults() { return of(DEFAULT_LANE); }

        public CallOptions routingKey(String routingKey) { return new CallOptions(lane, routingKey, requestId, metadata); }
        public CallOptions requestId(String requestId) { return new CallOptions(lane, routingKey, requestId, metadata); }
        public CallOptions metadata(Metadata metadata) { return new CallOptions(lane, routingKey, requestId, metadata); }
    }

    private final AiTransport transport;
    private final AiJson json;
    private final Supplier<String> idGenerator;
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
        idGenerator = builder.idGenerator;
        shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();
        observing = builder.metrics != Metrics.noop();
        Map<String, Lane> built = new LinkedHashMap<>();
        builder.lanes.forEach((name, spec) -> built.put(name, new Lane(name, spec, builder.breakers.get(name), builder.metrics)));
        lanes = Map.copyOf(built);
        LOG.log(Level.INFO, "AI client started with lanes {0}", lanes.keySet());
    }

    public static Builder builder(AiTransport transport) { return new Builder(transport); }

    /** Pre-allocate a request id from this client's configured generator (useful to correlate cancellations). */
    public String nextRequestId() {
        return Objects.requireNonNull(idGenerator.get(), "idGenerator returned null");
    }

    /** Call an external AI operation on the default lane with empty metadata. */
    public <T> CompletableFuture<T> call(String operation, Object request, Class<T> response, Duration timeout) {
        return call(CallOptions.defaults(), operation, request, response, timeout);
    }

    /** Call an external AI operation on {@code lane} with {@code metadata}. */
    public <T> CompletableFuture<T> call(String lane, String operation, Metadata metadata, Object request,
                                         Class<T> response, Duration timeout) {
        return call(CallOptions.of(lane).metadata(metadata), operation, request, response, timeout);
    }

    /**
     * Call an external AI operation with full {@link CallOptions} (lane, optional routingKey, optional requestId, metadata).
     * Completes once with the decoded result or an {@link AiException} carrying one of {@link cn.managame.FrameworkErrorCodes}
     * ({@code AI_CLOSED}, {@code AI_OVERLOADED}, {@code AI_TIMEOUT}, {@code AI_PROTOCOL_ERROR}, {@code AI_FAILED},
     * {@code AI_CIRCUIT_OPEN}), or cancellation.
     */
    public <T> CompletableFuture<T> call(CallOptions options, String operation, Object request,
                                         Class<T> response, Duration timeout) {
        Objects.requireNonNull(options, "options");
        Lane target = requireLane(options.lane());
        validateCommon(operation, request, response);
        if (timeout == null || !timeout.isPositive()) throw new IllegalArgumentException("Positive timeout required");
        String reqId = options.requestId() != null ? options.requestId() : nextRequestId();
        return submit(new CallTask<>(target, reqId, operation, options.routingKey(), options.metadata(),
                request, response, null, 0L, timeout.toNanos()));
    }

    /** Stream typed chunks on the default lane with empty metadata. */
    public <T> CompletableFuture<Void> stream(String operation, Object request, Class<T> chunkType,
                                              Duration idleTimeout, Duration totalTimeout, Consumer<? super T> onChunk) {
        return stream(CallOptions.defaults(), operation, request, chunkType, idleTimeout, totalTimeout, onChunk);
    }

    /** Stream typed chunks on {@code lane} with {@code metadata}. */
    public <T> CompletableFuture<Void> stream(String lane, String operation, Metadata metadata, Object request,
                                              Class<T> chunkType, Duration idleTimeout, Duration totalTimeout,
                                              Consumer<? super T> onChunk) {
        return stream(CallOptions.of(lane).metadata(metadata), operation, request, chunkType, idleTimeout, totalTimeout, onChunk);
    }

    /**
     * Stream typed chunks with full {@link CallOptions}. Fails with {@code AI_TIMEOUT} if no chunk arrives for
     * {@code idleTimeout} (including initial stall) or when {@code totalTimeout} elapses. Calling {@code cancel(true)}
     * on the returned future immediately interrupts blocking streams or closes the async cancel handle.
     */
    public <T> CompletableFuture<Void> stream(CallOptions options, String operation, Object request, Class<T> chunkType,
                                              Duration idleTimeout, Duration totalTimeout, Consumer<? super T> onChunk) {
        Objects.requireNonNull(options, "options");
        Lane target = requireLane(options.lane());
        validateCommon(operation, request, chunkType);
        Objects.requireNonNull(onChunk, "onChunk");
        if (idleTimeout == null || !idleTimeout.isPositive() || totalTimeout == null || !totalTimeout.isPositive())
            throw new IllegalArgumentException("Positive idleTimeout and totalTimeout required");
        long totalNanos = totalTimeout.toNanos();
        long idleNanos = Math.min(idleTimeout.toNanos(), totalNanos);
        String reqId = options.requestId() != null ? options.requestId() : nextRequestId();
        return submit(new CallTask<>(target, reqId, operation, options.routingKey(), options.metadata(),
                request, chunkType, onChunk, idleNanos, totalNanos));
    }

    private Lane requireLane(String name) {
        Lane lane = lanes.get(Objects.requireNonNull(name, "lane"));
        if (lane == null) throw new IllegalArgumentException("Unknown AI lane: " + name);
        return lane;
    }

    private static void validateCommon(String operation, Object request, Class<?> type) {
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("Non-blank operation required");
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
        if (lane.breaker != null) {
            int acquired = lane.breaker.tryAcquire();
            if (acquired == CircuitBreaker.REJECTED) {
                task.inner.completeExceptionally(new AiException(AI_CIRCUIT_OPEN, "AI lane '" + lane.name + "' circuit breaker is open"));
                return task.exposed;
            }
            task.circuitProbe = acquired == CircuitBreaker.PROBE;
        }
        if (lane.maxPerKey > 0 && task.routingKey != null) {
            CallTask<?>[] evicted = new CallTask<?>[1];
            boolean[] admitted = new boolean[1];
            lane.byKey.compute(task.routingKey, (key, deque) -> {
                if (deque == null) deque = new ArrayDeque<>(Math.min(lane.maxPerKey, 4));
                if (deque.size() < lane.maxPerKey) {
                    deque.addLast(task);
                    admitted[0] = true;
                } else if (lane.keyConflict == KeyConflict.CANCEL_PREVIOUS) {
                    evicted[0] = deque.pollFirst();
                    deque.addLast(task);
                    admitted[0] = true;
                }
                return deque;
            });
            if (!admitted[0]) {
                task.inner.completeExceptionally(new AiException(AI_OVERLOADED,
                        "AI key '" + task.routingKey + "' reached max in-flight " + lane.maxPerKey));
                return task.exposed;
            }
            task.trackedByKey = true;
            if (evicted[0] != null) evicted[0].inner.cancel(true);
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

    /** Returns true when lane permit and inFlight ownership moved to an async transport stage or stream. */
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
        AiRequest wireRequest = new AiRequest(task.requestId, task.lane.name, task.operation, task.routingKey,
                task.metadata, payload, responseSchema(task.responseType));
        if (task.isStream()) {
            return executeStream(task, wireRequest, remaining, self);
        }
        CompletionStage<String> stage;
        try {
            stage = transport.callAsync(wireRequest, Duration.ofNanos(remaining));
        } catch (Throwable failure) {
            task.inner.completeExceptionally(new AiException(AI_FAILED, "AI transport failed for " + task.operation, failure));
            return false;
        }
        if (stage != null) {
            task.beginHandoff();
            task.asyncStage = stage;
            if (task.inner.isDone()) {
                if (stage instanceof Future<?> f) f.cancel(true);
                task.releaseHandoff();
                return true;
            }
            stage.whenComplete((raw, error) -> {
                try {
                    completeCall(task, raw, error);
                } finally {
                    task.releaseHandoff();
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
    private <R> boolean executeStream(CallTask<R> task, AiRequest wireRequest, long remaining, Thread self) {
        Consumer<Object> sink = (Consumer<Object>) task.onChunk;
        AiTransport.StreamObserver observer = new AiTransport.StreamObserver() {
            @Override public boolean onChunk(String rawChunk) {
                if (task.inner.isDone()) return false;
                long now = System.nanoTime();
                if (task.totalNanos - (now - task.startedNanos) <= 0) {
                    task.failTimeout("AI stream timed out: " + task.operation);
                    return false;
                }
                task.lastActivityNanos = now;
                if (observing && task.firstChunkSeen.compareAndSet(false, true)) {
                    task.lane.ttft.record(Math.max(0L, now - task.startedNanos));
                }
                Object chunk;
                try {
                    chunk = json.decode(rawChunk, task.responseType);
                } catch (Throwable failure) {
                    task.inner.completeExceptionally(new AiException(AI_PROTOCOL_ERROR, "Cannot decode AI stream chunk for " + task.operation, failure));
                    return false;
                }
                try {
                    sink.accept(chunk);
                } catch (Throwable failure) {
                    task.inner.completeExceptionally(new AiException(AI_FAILED, "AI stream consumer failed for " + task.operation, failure));
                    return false;
                }
                return !task.inner.isDone();
            }

            @Override public void onComplete() {
                try {
                    if (!task.inner.isDone()) {
                        if (task.remainingNanos() <= 0) task.failTimeout("AI stream timed out: " + task.operation);
                        else task.inner.complete(null);
                    }
                } finally {
                    task.releaseHandoff();
                }
            }

            @Override public void onError(Throwable error) {
                try {
                    if (!task.inner.isDone()) {
                        Throwable cause = unwrap(error);
                        if (cause instanceof AiException ai) task.inner.completeExceptionally(ai);
                        else if (cause instanceof TimeoutException) task.failTimeout("AI stream timed out: " + task.operation);
                        else task.inner.completeExceptionally(new AiException(AI_FAILED, "AI stream failed for " + task.operation, cause));
                    }
                } finally {
                    task.releaseHandoff();
                }
            }
        };

        AutoCloseable handle;
        try {
            handle = transport.streamAsync(wireRequest, Duration.ofNanos(remaining), observer);
        } catch (Throwable failure) {
            task.inner.completeExceptionally(new AiException(AI_FAILED, "AI stream failed for " + task.operation, failure));
            return false;
        }
        if (handle != null) {
            task.beginHandoff();
            task.cancelHandle = handle;
            if (task.inner.isDone()) {
                task.closeHandleOnce();
                task.releaseHandoff();
            }
            return true;
        }

        task.runningThread = self;
        try {
            transport.stream(wireRequest, Duration.ofNanos(remaining), rawChunk -> {
                if (task.inner.isDone() || Thread.currentThread().isInterrupted())
                    throw new AiException(AI_TIMEOUT, "AI stream stopped: " + task.operation);
                long now = System.nanoTime();
                if (task.totalNanos - (now - task.startedNanos) <= 0)
                    throw new AiException(AI_TIMEOUT, "AI stream timed out: " + task.operation);
                task.lastActivityNanos = now;
                if (observing && task.firstChunkSeen.compareAndSet(false, true)) {
                    task.lane.ttft.record(Math.max(0L, now - task.startedNanos));
                }
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
        return false;
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
     * Any work still running or queued past the timeout is interrupted/closed and failed with {@code AI_CLOSED}.
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
            if (lane.byKey != null) lane.byKey.clear();
            lane.meters.forEach(Meter::close);
        });
        if (interrupted) Thread.currentThread().interrupt();
        LOG.log(Level.INFO, "AI client closed");
    }

    private record LaneSpec(int workers, int capacity, int maxPerKey, KeyConflict keyConflict) {}
    private record BreakerSpec(int failureThreshold, Duration openDuration) {}

    /** Lock-free 3-state circuit breaker per lane. */
    private static final class CircuitBreaker {
        static final int REJECTED = 0, ADMITTED = 1, PROBE = 2;
        private static final int CLOSED = 0, OPEN = 1, HALF_OPEN = 2;
        private final int threshold;
        private final long openNanos;
        private final AtomicInteger state = new AtomicInteger(CLOSED);
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openUntilNanos = new AtomicLong();

        CircuitBreaker(int threshold, long openNanos) {
            this.threshold = threshold;
            this.openNanos = openNanos;
        }

        int tryAcquire() {
            int s = state.get();
            if (s == CLOSED) return ADMITTED;
            if (s == HALF_OPEN) return REJECTED;
            if (System.nanoTime() - openUntilNanos.get() < 0) return REJECTED;
            return state.compareAndSet(OPEN, HALF_OPEN) ? PROBE : REJECTED;
        }

        void onSuccess() {
            consecutiveFailures.set(0);
            state.set(CLOSED);
        }

        void onFailure(boolean probe) {
            if (probe) {
                openUntilNanos.set(System.nanoTime() + openNanos);
                state.set(OPEN);
                return;
            }
            if (consecutiveFailures.incrementAndGet() >= threshold) {
                openUntilNanos.set(System.nanoTime() + openNanos);
                state.compareAndSet(CLOSED, OPEN);
            }
        }

        void releaseAbortedProbe() {
            state.compareAndSet(HALF_OPEN, OPEN);
        }

        boolean isOpen() { return state.get() == OPEN; }
    }

    private static final class Lane {
        final String name;
        final ArrayBlockingQueue<CallTask<?>> queue;
        final Semaphore permits;
        final int maxPerKey;
        final KeyConflict keyConflict;
        final ConcurrentHashMap<String, Deque<CallTask<?>>> byKey;
        final CircuitBreaker breaker;
        final ThreadFactory threads;
        final Counter succeeded, refused, failed;
        final LatencyBuckets latency = LatencyBuckets.ofMillis(AI_LATENCY_BOUNDS_MS);
        final LatencyBuckets ttft = LatencyBuckets.ofMillis(AI_LATENCY_BOUNDS_MS);
        final List<Meter> meters;

        Lane(String name, LaneSpec spec, BreakerSpec breakerSpec, Metrics metrics) {
            this.name = name;
            queue = new ArrayBlockingQueue<>(spec.capacity());
            permits = new Semaphore(spec.workers());
            maxPerKey = spec.maxPerKey();
            keyConflict = spec.keyConflict();
            byKey = maxPerKey > 0 ? new ConcurrentHashMap<>() : null;
            breaker = breakerSpec != null ? new CircuitBreaker(breakerSpec.failureThreshold(), breakerSpec.openDuration().toNanos()) : null;
            threads = Thread.ofVirtual().name("game-ai-" + name + "-", 0).factory();
            succeeded = metrics.counter("managame.ai.client.succeeded", "lane", name);
            refused = metrics.counter("managame.ai.client.refused", "lane", name);
            failed = metrics.counter("managame.ai.client.failed", "lane", name);
            List<Meter> registered = new ArrayList<>();
            registered.add(metrics.gauge("managame.ai.client.queued", queue::size, "lane", name));
            if (breaker != null) {
                registered.add(metrics.gauge("managame.ai.client.circuit_open", () -> breaker.isOpen() ? 1.0 : 0.0, "lane", name));
            }
            registered.addAll(latency.report(metrics, "managame.ai.client.latency", "lane", name));
            registered.addAll(ttft.report(metrics, "managame.ai.client.ttft", "lane", name));
            meters = List.copyOf(registered);
        }
    }

    private final class CallTask<R> {
        final Lane lane;
        final String requestId;
        final String operation;
        final String routingKey;
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
        final AtomicBoolean firstChunkSeen = new AtomicBoolean();
        final AtomicBoolean handleClosed = new AtomicBoolean();
        final AtomicBoolean permitReleased = new AtomicBoolean();
        volatile boolean trackedByKey;
        volatile boolean circuitProbe;
        volatile boolean handoffActive;
        volatile long lastActivityNanos;
        volatile CompletableFuture<Void> idleWatch;
        volatile Thread runningThread;
        volatile CompletionStage<?> asyncStage;
        volatile AutoCloseable cancelHandle;

        CallTask(Lane lane, String requestId, String operation, String routingKey, Metadata metadata,
                 Object request, Class<?> responseType, Consumer<?> onChunk, long idleNanos, long totalNanos) {
            this.lane = lane;
            this.requestId = requestId;
            this.operation = operation;
            this.routingKey = routingKey;
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

        void beginHandoff() { handoffActive = true; }

        void releaseHandoff() {
            if (handoffActive && permitReleased.compareAndSet(false, true)) {
                lane.permits.release();
                if (!forceStopped && !lane.queue.isEmpty()) maybeStartWorker(lane);
                finishFlight();
            }
        }

        void closeHandleOnce() {
            AutoCloseable handle = cancelHandle;
            if (handle != null && handleClosed.compareAndSet(false, true)) {
                try { handle.close(); }
                catch (Throwable error) { LOG.log(Level.WARNING, "Failed to close AI stream cancel handle for " + operation, error); }
            }
        }

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
            if (trackedByKey && lane.byKey != null && routingKey != null) {
                lane.byKey.computeIfPresent(routingKey, (key, deque) -> {
                    deque.remove(this);
                    return deque.isEmpty() ? null : deque;
                });
            }
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
                updateCircuitBreaker(translated);
                if (translated == null) {
                    lane.succeeded.increment();
                } else if (translated instanceof AiException ai
                        && (ai.errorCode() == AI_OVERLOADED || ai.errorCode() == AI_CLOSED || ai.errorCode() == AI_CIRCUIT_OPEN)) {
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
                closeHandleOnce();
                releaseHandoff();
            }
        }

        private void updateCircuitBreaker(Throwable translated) {
            if (lane.breaker == null) return;
            if (translated == null) {
                lane.breaker.onSuccess();
            } else if (translated instanceof AiException ai
                    && (ai.errorCode() == AI_FAILED || ai.errorCode() == AI_TIMEOUT)) {
                lane.breaker.onFailure(circuitProbe);
            } else if (circuitProbe) {
                lane.breaker.releaseAbortedProbe();
            }
        }
    }

    public static final class Builder {
        private final AiTransport transport;
        private final Map<String, LaneSpec> lanes = new LinkedHashMap<>();
        private final Map<String, BreakerSpec> breakers = new LinkedHashMap<>();
        private AiJson json = AiJson.jackson();
        private Supplier<String> idGenerator = () -> UUID.randomUUID().toString();
        private Metrics metrics = Metrics.noop();
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

        private Builder(AiTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            lanes.put(DEFAULT_LANE, new LaneSpec(DEFAULT_WORKERS, DEFAULT_CAPACITY, 0, KeyConflict.REJECT));
        }

        /** Configure the default lane's concurrency and queue capacity. */
        public Builder defaultLane(int workers, int capacity) { return lane(DEFAULT_LANE, workers, capacity); }

        /** Define or override an isolated bulkhead lane without per-key limits. */
        public Builder lane(String name, int workers, int capacity) {
            return registerLane(name, workers, capacity, 0, KeyConflict.REJECT);
        }

        /**
         * Define or override an isolated bulkhead lane with per-{@code routingKey} concurrency control.
         * Use {@link KeyConflict#CANCEL_PREVIOUS} with {@code maxPerKey = 1} to keep stateful per-session streams
         * (such as one player chatting with one NPC) mutually exclusive, or {@link KeyConflict#REJECT} to cap per-player
         * concurrency and prevent one actor from exhausting the lane.
         */
        public Builder lane(String name, int workers, int capacity, int maxPerKey, KeyConflict conflict) {
            if (maxPerKey < 1) throw new IllegalArgumentException("Positive maxPerKey required");
            Objects.requireNonNull(conflict, "conflict");
            return registerLane(name, workers, capacity, maxPerKey, conflict);
        }

        private Builder registerLane(String name, int workers, int capacity, int maxPerKey, KeyConflict conflict) {
            if (name == null || !name.matches("[a-z0-9_-]{1,32}"))
                throw new IllegalArgumentException("Invalid lane name: " + name);
            if (workers < 1 || capacity < 1) throw new IllegalArgumentException("Positive lane limits required");
            lanes.put(name, new LaneSpec(workers, capacity, maxPerKey, conflict));
            return this;
        }

        /**
         * Enable a circuit breaker on {@code lane}: after {@code failureThreshold} consecutive transport/timeout
         * failures, new invocations fast-fail with {@code AI_CIRCUIT_OPEN} for {@code openDuration}, then allow
         * one probe invocation to test recovery.
         */
        public Builder circuitBreaker(String lane, int failureThreshold, Duration openDuration) {
            if (!lanes.containsKey(lane)) throw new IllegalArgumentException("Define lane before its circuit breaker: " + lane);
            if (failureThreshold < 1 || openDuration == null || !openDuration.isPositive())
                throw new IllegalArgumentException("Positive failureThreshold and openDuration required");
            breakers.put(lane, new BreakerSpec(failureThreshold, openDuration));
            return this;
        }

        public Builder json(AiJson json) { this.json = Objects.requireNonNull(json, "json"); return this; }

        public Builder idGenerator(Supplier<String> idGenerator) {
            this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
            return this;
        }

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
