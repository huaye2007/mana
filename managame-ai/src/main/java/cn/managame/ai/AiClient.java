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
 * Outbound client for calling external AI service processes with per-service isolation, optional per-key
 * concurrency/session control ({@link KeyConflict}), and optional per-service circuit breaking.
 * Supports typed request-response ({@link #call}, blocking or non-blocking via {@link AiTransport#callAsync}) and
 * typed streaming ({@link #stream}, blocking or non-blocking via {@link AiTransport#streamAsync}) with independent
 * idle and total timeouts and TTFT latency buckets sized for external AI workloads (50ms to 30s).
 * Memory, prompts, model routing and third-party AI SDKs stay in the external AI service processes.
 * Close after stopping submissions and before closing the game runtime.
 */
public final class AiClient implements AutoCloseable {
    public static final String DEFAULT_SERVICE = "default";
    public static final int DEFAULT_WORKERS = 8;
    public static final int DEFAULT_CAPACITY = 64;
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final long[] AI_LATENCY_BOUNDS_MS = {50, 100, 250, 500, 1000, 2000, 5000, 10000, 30000};
    private static final System.Logger LOG = System.getLogger("cn.managame.ai");

    /** How a service handles concurrent invocations sharing the same non-null {@code routingKey}. */
    public enum KeyConflict {
        /** Reject the new invocation with {@code AI_OVERLOADED} once the key hits its per-key limit. */
        REJECT,
        /** Cancel the oldest in-flight invocation for the same key and admit the new one (e.g. interrupting NPC chat). */
        CANCEL_PREVIOUS
    }

    /** Per-invocation target service, routing, correlation and metadata options. */
    public record CallOptions(String service, String routingKey, String requestId, Metadata metadata) {
        public CallOptions {
            if (service == null || service.isBlank()) throw new IllegalArgumentException("Empty service");
            Objects.requireNonNull(metadata, "metadata");
        }

        public static CallOptions service(String service) { return new CallOptions(service, null, null, Metadata.empty()); }
        public static CallOptions defaults() { return service(DEFAULT_SERVICE); }

        public CallOptions routingKey(String routingKey) { return new CallOptions(service, routingKey, requestId, metadata); }
        public CallOptions requestId(String requestId) { return new CallOptions(service, routingKey, requestId, metadata); }
        public CallOptions metadata(Metadata metadata) { return new CallOptions(service, routingKey, requestId, metadata); }
    }

    /** Fluent configuration for one isolated external AI service (concurrency, queue, per-key policy, circuit breaker). */
    public static final class ServiceConfig {
        private int workers = DEFAULT_WORKERS;
        private int capacity = DEFAULT_CAPACITY;
        private int maxPerKey;
        private KeyConflict keyConflict = KeyConflict.REJECT;
        private BreakerSpec breaker;

        private ServiceConfig() {}

        /** Set max concurrent invocations and bounded wait queue capacity. */
        public ServiceConfig limits(int workers, int capacity) {
            if (workers < 1 || capacity < 1) throw new IllegalArgumentException("Positive service limits required");
            this.workers = workers;
            this.capacity = capacity;
            return this;
        }

        /**
         * Cap concurrent/queued invocations sharing the same non-null {@code routingKey}.
         * Use {@link KeyConflict#CANCEL_PREVIOUS} with {@code maxPerKey = 1} to keep stateful per-session streams
         * (such as one player chatting with one NPC) mutually exclusive, or {@link KeyConflict#REJECT} for fairness.
         */
        public ServiceConfig perKey(int maxPerKey, KeyConflict conflict) {
            if (maxPerKey < 1) throw new IllegalArgumentException("Positive maxPerKey required");
            this.maxPerKey = maxPerKey;
            this.keyConflict = Objects.requireNonNull(conflict, "conflict");
            return this;
        }

        /**
         * Open this service's circuit breaker after {@code failureThreshold} consecutive transport/timeout failures,
         * fast-failing with {@code AI_CIRCUIT_OPEN} for {@code openDuration} before allowing a single recovery probe.
         */
        public ServiceConfig circuitBreaker(int failureThreshold, Duration openDuration) {
            if (failureThreshold < 1 || openDuration == null || !openDuration.isPositive())
                throw new IllegalArgumentException("Positive failureThreshold and openDuration required");
            this.breaker = new BreakerSpec(failureThreshold, openDuration);
            return this;
        }

        private ServiceSpec toSpec() {
            return new ServiceSpec(workers, capacity, maxPerKey, keyConflict, breaker);
        }
    }

    private final AiTransport transport;
    private final AiJson json;
    private final Supplier<String> idGenerator;
    private final Map<String, Service> services;
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
        Map<String, Service> built = new LinkedHashMap<>();
        builder.services.forEach((name, spec) -> built.put(name, new Service(name, spec, builder.metrics)));
        services = Map.copyOf(built);
        LOG.log(Level.INFO, "AI client started with services {0}", services.keySet());
    }

    public static Builder builder(AiTransport transport) { return new Builder(transport); }

    /** Pre-allocate a request id from this client's configured generator (useful to correlate cancellations). */
    public String nextRequestId() {
        return Objects.requireNonNull(idGenerator.get(), "idGenerator returned null");
    }

    /** Call an external AI operation on the default service with empty metadata. */
    public <T> CompletableFuture<T> call(String operation, Object request, Class<T> response, Duration timeout) {
        return call(CallOptions.defaults(), operation, request, response, timeout);
    }

    /** Call an external AI operation on {@code service} with {@code metadata}. */
    public <T> CompletableFuture<T> call(String service, String operation, Metadata metadata, Object request,
                                         Class<T> response, Duration timeout) {
        return call(CallOptions.service(service).metadata(metadata), operation, request, response, timeout);
    }

    /**
     * Call an external AI operation with full {@link CallOptions} (service, optional routingKey, optional requestId, metadata).
     * Completes once with the decoded result or an {@link AiException} carrying one of {@link cn.managame.FrameworkErrorCodes}
     * ({@code AI_CLOSED}, {@code AI_OVERLOADED}, {@code AI_TIMEOUT}, {@code AI_PROTOCOL_ERROR}, {@code AI_FAILED},
     * {@code AI_CIRCUIT_OPEN}), or cancellation.
     */
    public <T> CompletableFuture<T> call(CallOptions options, String operation, Object request,
                                         Class<T> response, Duration timeout) {
        Objects.requireNonNull(options, "options");
        Service target = requireService(options.service());
        validateCommon(operation, request, response);
        if (timeout == null || !timeout.isPositive()) throw new IllegalArgumentException("Positive timeout required");
        String reqId = options.requestId() != null ? options.requestId() : nextRequestId();
        return submit(new CallTask<>(target, reqId, operation, options.routingKey(), options.metadata(),
                request, response, null, 0L, timeout.toNanos()));
    }

    /** Stream typed chunks on the default service with empty metadata. */
    public <T> CompletableFuture<Void> stream(String operation, Object request, Class<T> chunkType,
                                              Duration idleTimeout, Duration totalTimeout, Consumer<? super T> onChunk) {
        return stream(CallOptions.defaults(), operation, request, chunkType, idleTimeout, totalTimeout, onChunk);
    }

    /** Stream typed chunks on {@code service} with {@code metadata}. */
    public <T> CompletableFuture<Void> stream(String service, String operation, Metadata metadata, Object request,
                                              Class<T> chunkType, Duration idleTimeout, Duration totalTimeout,
                                              Consumer<? super T> onChunk) {
        return stream(CallOptions.service(service).metadata(metadata), operation, request, chunkType, idleTimeout, totalTimeout, onChunk);
    }

    /**
     * Stream typed chunks with full {@link CallOptions}. Fails with {@code AI_TIMEOUT} if no chunk arrives for
     * {@code idleTimeout} (including initial stall) or when {@code totalTimeout} elapses. Calling {@code cancel(true)}
     * on the returned future immediately interrupts blocking streams or closes the async cancel handle.
     */
    public <T> CompletableFuture<Void> stream(CallOptions options, String operation, Object request, Class<T> chunkType,
                                              Duration idleTimeout, Duration totalTimeout, Consumer<? super T> onChunk) {
        Objects.requireNonNull(options, "options");
        Service target = requireService(options.service());
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

    private Service requireService(String name) {
        Service service = services.get(Objects.requireNonNull(name, "service"));
        if (service == null) throw new IllegalArgumentException("Unknown AI service: " + name);
        return service;
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
        Service service = task.service;
        if (closed) {
            task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient is closed"));
            return task.exposed;
        }
        if (service.breaker != null) {
            int acquired = service.breaker.tryAcquire();
            if (acquired == CircuitBreaker.REJECTED) {
                task.inner.completeExceptionally(new AiException(AI_CIRCUIT_OPEN, "AI service '" + service.name + "' circuit breaker is open"));
                return task.exposed;
            }
            task.circuitProbe = acquired == CircuitBreaker.PROBE;
        }
        if (service.maxPerKey > 0 && task.routingKey != null) {
            CallTask<?>[] evicted = new CallTask<?>[1];
            boolean[] admitted = new boolean[1];
            service.byKey.compute(task.routingKey, (key, deque) -> {
                if (deque == null) deque = new ArrayDeque<>(Math.min(service.maxPerKey, 4));
                if (deque.size() < service.maxPerKey) {
                    deque.addLast(task);
                    admitted[0] = true;
                } else if (service.keyConflict == KeyConflict.CANCEL_PREVIOUS) {
                    evicted[0] = deque.pollFirst();
                    deque.addLast(task);
                    admitted[0] = true;
                }
                return deque;
            });
            if (!admitted[0]) {
                task.inner.completeExceptionally(new AiException(AI_OVERLOADED,
                        "AI key '" + task.routingKey + "' reached max in-flight " + service.maxPerKey));
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
        if (service.permits.tryAcquire()) {
            if (closed) {
                service.permits.release();
                task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient is closed"));
                return task.exposed;
            }
            startWorker(service, task);
            return task.exposed;
        }
        if (!service.queue.offer(task)) {
            task.inner.completeExceptionally(new AiException(AI_OVERLOADED, "AI service '" + service.name + "' queue is full"));
            return task.exposed;
        }
        if (closed && service.queue.remove(task)) {
            task.inner.completeExceptionally(new AiException(AI_CLOSED, "AiClient is closed"));
            return task.exposed;
        }
        maybeStartWorker(service);
        return task.exposed;
    }

    private void maybeStartWorker(Service service) {
        if (!forceStopped && !service.queue.isEmpty() && service.permits.tryAcquire()) {
            CallTask<?> next = service.queue.poll();
            if (next == null) {
                service.permits.release();
                return;
            }
            startWorker(service, next);
        }
    }

    private void startWorker(Service service, CallTask<?> first) {
        inFlight.incrementAndGet();
        try {
            service.threads.newThread(() -> runWorker(service, first)).start();
        } catch (Throwable failure) {
            service.permits.release();
            first.inner.completeExceptionally(new AiException(AI_FAILED, "Cannot start AI worker", failure));
            finishFlight();
        }
    }

    private void runWorker(Service service, CallTask<?> first) {
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
                task = service.queue.poll();
            }
        } finally {
            activeWorkers.remove(self);
            if (!handedOff) {
                service.permits.release();
                if (!forceStopped && !service.queue.isEmpty()) maybeStartWorker(service);
                finishFlight();
            }
        }
    }

    /** Returns true when service permit and inFlight ownership moved to an async transport stage or stream. */
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
        AiRequest wireRequest = new AiRequest(task.requestId, task.service.name, task.operation, task.routingKey,
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
                    task.service.ttft.record(Math.max(0L, now - task.startedNanos));
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
                    task.service.ttft.record(Math.max(0L, now - task.startedNanos));
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
        for (Service service : services.values()) if (!service.queue.isEmpty()) return false;
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
        services.values().forEach(this::maybeStartWorker);
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
        services.values().forEach(service -> {
            service.queue.clear();
            if (service.byKey != null) service.byKey.clear();
            service.meters.forEach(Meter::close);
        });
        if (interrupted) Thread.currentThread().interrupt();
        LOG.log(Level.INFO, "AI client closed");
    }

    private record BreakerSpec(int failureThreshold, Duration openDuration) {}
    private record ServiceSpec(int workers, int capacity, int maxPerKey, KeyConflict keyConflict, BreakerSpec breaker) {}

    /** Lock-free 3-state circuit breaker per service. */
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

    private static final class Service {
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

        Service(String name, ServiceSpec spec, Metrics metrics) {
            this.name = name;
            queue = new ArrayBlockingQueue<>(spec.capacity());
            permits = new Semaphore(spec.workers());
            maxPerKey = spec.maxPerKey();
            keyConflict = spec.keyConflict();
            byKey = maxPerKey > 0 ? new ConcurrentHashMap<>() : null;
            breaker = spec.breaker() != null
                    ? new CircuitBreaker(spec.breaker().failureThreshold(), spec.breaker().openDuration().toNanos()) : null;
            threads = Thread.ofVirtual().name("game-ai-" + name + "-", 0).factory();
            succeeded = metrics.counter("managame.ai.client.succeeded", "service", name);
            refused = metrics.counter("managame.ai.client.refused", "service", name);
            failed = metrics.counter("managame.ai.client.failed", "service", name);
            List<Meter> registered = new ArrayList<>();
            registered.add(metrics.gauge("managame.ai.client.queued", queue::size, "service", name));
            if (breaker != null) {
                registered.add(metrics.gauge("managame.ai.client.circuit_open", () -> breaker.isOpen() ? 1.0 : 0.0, "service", name));
            }
            registered.addAll(latency.report(metrics, "managame.ai.client.latency", "service", name));
            registered.addAll(ttft.report(metrics, "managame.ai.client.ttft", "service", name));
            meters = List.copyOf(registered);
        }
    }

    private final class CallTask<R> {
        final Service service;
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

        CallTask(Service service, String requestId, String operation, String routingKey, Metadata metadata,
                 Object request, Class<?> responseType, Consumer<?> onChunk, long idleNanos, long totalNanos) {
            this.service = service;
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
                service.permits.release();
                if (!forceStopped && !service.queue.isEmpty()) maybeStartWorker(service);
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
            if (trackedByKey && service.byKey != null && routingKey != null) {
                service.byKey.computeIfPresent(routingKey, (key, deque) -> {
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
                if (observing) service.latency.record(Math.max(0L, System.nanoTime() - startedNanos));
                updateCircuitBreaker(translated);
                if (translated == null) {
                    service.succeeded.increment();
                } else if (translated instanceof AiException ai
                        && (ai.errorCode() == AI_OVERLOADED || ai.errorCode() == AI_CLOSED || ai.errorCode() == AI_CIRCUIT_OPEN)) {
                    service.refused.increment();
                } else {
                    service.failed.increment();
                }
            }
            if (translated != null) {
                service.queue.remove(this);
                Thread thread = runningThread;
                if (thread != null) thread.interrupt();
                CompletionStage<?> stage = asyncStage;
                if (stage instanceof Future<?> future) future.cancel(true);
                closeHandleOnce();
                releaseHandoff();
            }
        }

        private void updateCircuitBreaker(Throwable translated) {
            if (service.breaker == null) return;
            if (translated == null) {
                service.breaker.onSuccess();
            } else if (translated instanceof AiException ai
                    && (ai.errorCode() == AI_FAILED || ai.errorCode() == AI_TIMEOUT)) {
                service.breaker.onFailure(circuitProbe);
            } else if (circuitProbe) {
                service.breaker.releaseAbortedProbe();
            }
        }
    }

    public static final class Builder {
        private final AiTransport transport;
        private final Map<String, ServiceSpec> services = new LinkedHashMap<>();
        private AiJson json = AiJson.jackson();
        private Supplier<String> idGenerator = () -> UUID.randomUUID().toString();
        private Metrics metrics = Metrics.noop();
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

        private Builder(AiTransport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            services.put(DEFAULT_SERVICE, new ServiceConfig().toSpec());
        }

        /** Configure the default service's concurrency and queue capacity. */
        public Builder defaultService(int workers, int capacity) {
            return service(DEFAULT_SERVICE, s -> s.limits(workers, capacity));
        }

        /** Configure the default service with full {@link ServiceConfig} options. */
        public Builder defaultService(Consumer<ServiceConfig> configure) {
            return service(DEFAULT_SERVICE, configure);
        }

        /** Define or override an isolated external AI service with concurrency and queue limits. */
        public Builder service(String name, int workers, int capacity) {
            return service(name, s -> s.limits(workers, capacity));
        }

        /**
         * Define or override an isolated external AI service (for example {@code "decision"}, {@code "chat"}).
         * Service names appear in metric tags and must be lowercase identifiers.
         */
        public Builder service(String name, Consumer<ServiceConfig> configure) {
            if (name == null || !name.matches("[a-z0-9_-]{1,32}"))
                throw new IllegalArgumentException("Invalid service name: " + name);
            Objects.requireNonNull(configure, "configure");
            ServiceConfig config = new ServiceConfig();
            configure.accept(config);
            services.put(name, config.toSpec());
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
