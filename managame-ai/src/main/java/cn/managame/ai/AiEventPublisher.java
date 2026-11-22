package cn.managame.ai;

import cn.managame.Metadata;
import cn.managame.metrics.Counter;
import cn.managame.metrics.Meter;
import cn.managame.metrics.Metrics;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Outbound event publisher for analytics, feature/memory pipelines and downstream subscribers.
 * Pass immutable event payloads. Registered event types bind to stable external names independent of Java class names.
 * Events are stamped with an id and occurrence timestamp at publish time, buffered in a bounded queue, and flushed to
 * {@link AiEventSink} in batches bounded by {@code batchSize}, {@code maxBatchBytes}, and {@code linger} on bounded
 * on-demand virtual threads (never pooled). Transient sink failures retry up to {@code maxRetries}; any undelivered
 * event is counted, logged, and handed to {@code errorHandler}. Close after stopping producers and before the runtime.
 */
public final class AiEventPublisher implements AutoCloseable {
    public static final int DEFAULT_WORKERS = 1;
    public static final int DEFAULT_CAPACITY = 4096;
    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final int DEFAULT_MAX_BATCH_BYTES = 1024 * 1024;
    public static final Duration DEFAULT_LINGER = Duration.ofMillis(50);
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(100);
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final System.Logger LOG = System.getLogger("cn.managame.ai");

    /** Stable external event name bound to a Java payload class. */
    public record EventType(String name, Class<?> type) {
        public EventType {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Empty event name");
            Objects.requireNonNull(type, "type");
        }
    }

    private record QueuedEvent(String eventId, String event, String partitionKey, long occurredAt,
                               Metadata metadata, Object payload) {}

    private static final class LingerSlot {
        final Thread thread;
        boolean waiting;
        LingerSlot(Thread thread) { this.thread = thread; }
    }

    private final Map<Class<?>, String> events;
    private final AiJson json;
    private final AiEventSink sink;
    private final Supplier<String> idGenerator;
    private final LongSupplier clock;
    private final Consumer<AiEventFailure> errorHandler;
    private final Predicate<Throwable> retryable;
    private final ArrayBlockingQueue<QueuedEvent> queue;
    private final Semaphore permits;
    private final int batchSize;
    private final int maxBatchBytes;
    private final long lingerNanos;
    private final int maxRetries;
    private final long retryDelayNanos;
    private final long shutdownTimeoutNanos;
    private final ThreadFactory threads = Thread.ofVirtual().name("game-ai-event-", 0).factory();

    private final Set<Thread> activeWorkers = ConcurrentHashMap.newKeySet();
    private final Set<LingerSlot> lingerSlots = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object drainLock = new Object();
    private volatile boolean closed;
    private volatile boolean forceStopped;

    private final Meter queueGauge;
    private final Counter sent, lost, batches, retried;

    private AiEventPublisher(Builder builder) {
        events = Map.copyOf(builder.events);
        json = builder.json;
        sink = builder.sink;
        idGenerator = builder.idGenerator;
        clock = builder.clock;
        errorHandler = builder.errorHandler;
        retryable = builder.retryable;
        queue = new ArrayBlockingQueue<>(builder.capacity);
        permits = new Semaphore(builder.workers);
        batchSize = builder.batchSize;
        maxBatchBytes = builder.maxBatchBytes;
        lingerNanos = builder.linger.toNanos();
        maxRetries = builder.maxRetries;
        retryDelayNanos = builder.retryDelay.toNanos();
        shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();

        Metrics metrics = builder.metrics;
        queueGauge = metrics.gauge("managame.ai.events.queued", queue::size);
        sent = metrics.counter("managame.ai.events.sent");
        lost = metrics.counter("managame.ai.events.lost");
        batches = metrics.counter("managame.ai.events.batches");
        retried = metrics.counter("managame.ai.events.retried");
        LOG.log(Level.INFO, "AI event publisher started with {0} event types", events.size());
    }

    public static Builder builder(AiEventSink sink) { return new Builder(sink); }

    /** Publish an unpartitioned event with empty metadata; returns false when dropped (closed or queue full). */
    public boolean publish(Object event) { return publish(null, Metadata.empty(), event); }

    /** Publish an event under {@code partitionKey} with empty metadata. */
    public boolean publish(String partitionKey, Object event) { return publish(partitionKey, Metadata.empty(), event); }

    /**
     * Enqueue an event for batched delivery and return immediately. Unregistered event classes are programming
     * errors ({@link IllegalArgumentException}). Dropped events are counted, logged, and passed to {@code errorHandler}.
     * For events that must survive a process crash, publish from a durable store or log pipeline.
     */
    public boolean publish(String partitionKey, Metadata metadata, Object event) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(event, "event");
        String name = events.get(event.getClass());
        if (name == null) throw new IllegalArgumentException("Unregistered event: " + event.getClass());
        long now = clock.getAsLong();
        String id = Objects.requireNonNull(idGenerator.get(), "idGenerator returned null");
        if (closed) {
            reportDrop(new AiEventFailure(id, name, partitionKey, now, metadata, event, "AiEventPublisher is closed", null));
            return false;
        }
        QueuedEvent item = new QueuedEvent(id, name, partitionKey, now, metadata, event);
        if (!queue.offer(item)) {
            reportDrop(new AiEventFailure(id, name, partitionKey, now, metadata, event, "event queue full", null));
            return false;
        }
        if (closed && queue.remove(item)) {
            reportDrop(new AiEventFailure(id, name, partitionKey, now, metadata, event, "AiEventPublisher is closed", null));
            return false;
        }
        maybeStartWorker();
        return true;
    }

    private void maybeStartWorker() {
        if (!forceStopped && !queue.isEmpty() && permits.tryAcquire()) {
            inFlight.incrementAndGet();
            try {
                threads.newThread(this::drain).start();
            } catch (Throwable failure) {
                permits.release();
                finishFlight();
                throw failure;
            }
        }
    }

    private void drain() {
        Thread self = Thread.currentThread();
        LingerSlot slot = new LingerSlot(self);
        activeWorkers.add(self);
        lingerSlots.add(slot);
        try {
            List<AiEvent> batch = new ArrayList<>(Math.min(batchSize, 64));
            AiEvent carried = null;
            int carriedBytes = 0;
            while (!forceStopped) {
                batch.clear();
                int batchBytes = 0;
                if (carried != null) {
                    batch.add(carried);
                    batchBytes = carriedBytes;
                    carried = null;
                    carriedBytes = 0;
                } else {
                    while (!forceStopped) {
                        QueuedEvent first = queue.poll();
                        if (first == null) break;
                        AiEvent encoded = encodeOne(first);
                        if (encoded != null) {
                            batch.add(encoded);
                            batchBytes = utf8Length(encoded.payload());
                            break;
                        }
                    }
                    if (batch.isEmpty()) break;
                }

                // Drain immediately available items without exceeding batchSize or maxBatchBytes
                while (batch.size() < batchSize && batchBytes < maxBatchBytes && !forceStopped) {
                    QueuedEvent next = queue.poll();
                    if (next == null) break;
                    AiEvent encoded = encodeOne(next);
                    if (encoded == null) continue;
                    int itemBytes = utf8Length(encoded.payload());
                    if (batchBytes + itemBytes > maxBatchBytes) {
                        carried = encoded;
                        carriedBytes = itemBytes;
                        break;
                    }
                    batch.add(encoded);
                    batchBytes += itemBytes;
                }

                // Wait up to linger window if neither size nor byte limit has been reached
                if (carried == null && batch.size() < batchSize && batchBytes < maxBatchBytes
                        && lingerNanos > 0 && !closed && !forceStopped) {
                    long deadline = System.nanoTime() + lingerNanos;
                    while (carried == null && batch.size() < batchSize && batchBytes < maxBatchBytes && !closed && !forceStopped) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) break;
                        synchronized (slot) {
                            if (closed || forceStopped) break;
                            slot.waiting = true;
                        }
                        QueuedEvent next;
                        try {
                            next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException wake) {
                            break;
                        } finally {
                            synchronized (slot) {
                                slot.waiting = false;
                                if (!forceStopped) Thread.interrupted();
                            }
                        }
                        if (next == null) break;
                        AiEvent encoded = encodeOne(next);
                        if (encoded == null) continue;
                        int itemBytes = utf8Length(encoded.payload());
                        if (batchBytes + itemBytes > maxBatchBytes) {
                            carried = encoded;
                            carriedBytes = itemBytes;
                            break;
                        }
                        batch.add(encoded);
                        batchBytes += itemBytes;
                        // Drain any extra items that arrived concurrently
                        while (batch.size() < batchSize && batchBytes < maxBatchBytes && !forceStopped) {
                            QueuedEvent more = queue.poll();
                            if (more == null) break;
                            AiEvent moreEncoded = encodeOne(more);
                            if (moreEncoded == null) continue;
                            int moreBytes = utf8Length(moreEncoded.payload());
                            if (batchBytes + moreBytes > maxBatchBytes) {
                                carried = moreEncoded;
                                carriedBytes = moreBytes;
                                break;
                            }
                            batch.add(moreEncoded);
                            batchBytes += moreBytes;
                        }
                    }
                }

                sendWithRetry(List.copyOf(batch));
            }
            if (carried != null) {
                reportDrop(new AiEventFailure(carried.eventId(), carried.event(), carried.partitionKey(),
                        carried.occurredAt(), carried.metadata(), carried.payload(), "AiEventPublisher closed before send", null));
            }
        } finally {
            lingerSlots.remove(slot);
            activeWorkers.remove(self);
            permits.release();
            if (!forceStopped && !queue.isEmpty()) maybeStartWorker();
            finishFlight();
        }
    }

    private AiEvent encodeOne(QueuedEvent item) {
        try {
            return new AiEvent(item.eventId(), item.event(), item.partitionKey(),
                    item.occurredAt(), item.metadata(), json.encode(item.payload()));
        } catch (Throwable failure) {
            reportDrop(new AiEventFailure(item.eventId(), item.event(), item.partitionKey(),
                    item.occurredAt(), item.metadata(), item.payload(), "encode failed", failure));
            return null;
        }
    }

    private void sendWithRetry(List<AiEvent> batch) {
        int attempts = 0;
        while (true) {
            try {
                sink.send(batch);
                sent.increment(batch.size());
                batches.increment();
                return;
            } catch (Throwable failure) {
                attempts++;
                if (attempts <= maxRetries && !forceStopped && retryable.test(failure)) {
                    retried.increment();
                    LOG.log(Level.WARNING, "Retrying AI event batch (" + batch.size() + ", attempt " + attempts + "/" + maxRetries + ")", failure);
                    if (retryDelayNanos > 0) {
                        try {
                            TimeUnit.NANOSECONDS.sleep(retryDelayNanos);
                        } catch (InterruptedException e) {
                            if (forceStopped) {
                                failBatch(batch, "interrupted during shutdown retry", failure);
                                return;
                            }
                        }
                    }
                    continue;
                }
                failBatch(batch, "sink delivery failed", failure);
                return;
            }
        }
    }

    private void failBatch(List<AiEvent> batch, String reason, Throwable failure) {
        lost.increment(batch.size());
        LOG.log(Level.ERROR, "Lost " + batch.size() + " AI event(s) starting with " + batch.getFirst().event() + ": " + reason, failure);
        for (AiEvent event : batch) {
            notifyErrorHandler(new AiEventFailure(event.eventId(), event.event(), event.partitionKey(),
                    event.occurredAt(), event.metadata(), event.payload(), reason, failure));
        }
    }

    private void reportDrop(AiEventFailure failure) {
        lost.increment();
        if (failure.cause() == null) LOG.log(Level.ERROR, "Lost AI event {0}: {1}", failure.event(), failure.reason());
        else LOG.log(Level.ERROR, "Lost AI event " + failure.event() + ": " + failure.reason(), failure.cause());
        notifyErrorHandler(failure);
    }

    private void notifyErrorHandler(AiEventFailure failure) {
        if (errorHandler == null) return;
        try {
            errorHandler.accept(failure);
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "AI event errorHandler failed for " + failure.event(), error);
        }
    }

    private static int utf8Length(String s) {
        int bytes = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else bytes += 3;
        }
        return bytes;
    }

    private void finishFlight() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized (drainLock) { drainLock.notifyAll(); }
        }
    }

    /**
     * Refuses new events, wakes lingering workers to flush immediately, and waits up to {@code shutdownTimeout}
     * for queued events to be delivered. Any work still pending after the timeout is interrupted/dropped. Idempotent.
     */
    @Override public void close() {
        synchronized (drainLock) {
            if (closed) return;
            closed = true;
        }
        for (LingerSlot slot : lingerSlots) {
            synchronized (slot) {
                if (slot.waiting) slot.thread.interrupt();
            }
        }
        maybeStartWorker();
        long deadline = System.nanoTime() + shutdownTimeoutNanos;
        boolean interrupted = false;
        synchronized (drainLock) {
            while (inFlight.get() > 0 || !queue.isEmpty()) {
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
        int dropped = 0;
        for (QueuedEvent item; (item = queue.poll()) != null; dropped++) {
            lost.increment();
            notifyErrorHandler(new AiEventFailure(item.eventId(), item.event(), item.partitionKey(),
                    item.occurredAt(), item.metadata(), item.payload(), "AiEventPublisher closed before send", null));
        }
        if (dropped > 0) LOG.log(Level.ERROR, "Dropped {0} queued AI event(s) on close", dropped);
        queueGauge.close();
        if (interrupted) Thread.currentThread().interrupt();
        LOG.log(Level.INFO, "AI event publisher closed");
    }

    public static final class Builder {
        private final AiEventSink sink;
        private final Map<Class<?>, String> events = new HashMap<>();
        private AiJson json = AiJson.jackson();
        private Supplier<String> idGenerator = () -> UUID.randomUUID().toString();
        private LongSupplier clock = System::currentTimeMillis;
        private Consumer<AiEventFailure> errorHandler;
        private Predicate<Throwable> retryable = failure -> true;
        private Metrics metrics = Metrics.noop();
        private int workers = DEFAULT_WORKERS;
        private int capacity = DEFAULT_CAPACITY;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private int maxBatchBytes = DEFAULT_MAX_BATCH_BYTES;
        private Duration linger = DEFAULT_LINGER;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration retryDelay = DEFAULT_RETRY_DELAY;
        private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

        private Builder(AiEventSink sink) { this.sink = Objects.requireNonNull(sink, "sink"); }

        public Builder events(Collection<EventType> types) {
            for (EventType event : types) {
                if (events.containsValue(event.name()) || events.putIfAbsent(event.type(), event.name()) != null)
                    throw new IllegalArgumentException("Duplicate event: " + event);
            }
            return this;
        }

        public Builder json(AiJson json) { this.json = Objects.requireNonNull(json, "json"); return this; }

        public Builder idGenerator(Supplier<String> idGenerator) {
            this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
            return this;
        }

        public Builder clock(LongSupplier epochMillis) {
            this.clock = Objects.requireNonNull(epochMillis, "clock");
            return this;
        }

        /** Optional fallback handler invoked for every dropped or failed event (for local spooling or alerting). */
        public Builder errorHandler(Consumer<AiEventFailure> errorHandler) {
            this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
            return this;
        }

        public Builder metrics(Metrics metrics) { this.metrics = Objects.requireNonNull(metrics, "metrics"); return this; }

        public Builder limits(int workers, int capacity) {
            return limits(workers, capacity, batchSize, linger);
        }

        public Builder limits(int workers, int capacity, int batchSize, Duration linger) {
            if (workers < 1 || capacity < 1 || batchSize < 1 || linger == null || linger.isNegative())
                throw new IllegalArgumentException("Invalid event publisher limits");
            this.workers = workers;
            this.capacity = capacity;
            this.batchSize = batchSize;
            this.linger = linger;
            return this;
        }

        /** Maximum encoded UTF-8 payload bytes per batch (default 1 MiB); a single oversized event still sends alone. */
        public Builder maxBatchBytes(int maxBatchBytes) {
            if (maxBatchBytes < 1) throw new IllegalArgumentException("Positive maxBatchBytes required");
            this.maxBatchBytes = maxBatchBytes;
            return this;
        }

        /** Configure bounded sink retries (0 disables retries) and backoff delay. */
        public Builder retry(int maxRetries, Duration retryDelay) {
            return retry(maxRetries, retryDelay, failure -> true);
        }

        /** Configure bounded sink retries, delay, and predicate classifying retryable failures. */
        public Builder retry(int maxRetries, Duration retryDelay, Predicate<Throwable> retryable) {
            if (maxRetries < 0 || retryDelay == null || retryDelay.isNegative())
                throw new IllegalArgumentException("Invalid retry settings");
            this.maxRetries = maxRetries;
            this.retryDelay = retryDelay;
            this.retryable = Objects.requireNonNull(retryable, "retryable");
            return this;
        }

        public Builder shutdownTimeout(Duration timeout) {
            if (timeout == null || !timeout.isPositive())
                throw new IllegalArgumentException("Positive shutdown timeout required");
            shutdownTimeout = timeout;
            return this;
        }

        public AiEventPublisher build() { return new AiEventPublisher(this); }
    }
}
