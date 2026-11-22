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
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Outbound event publisher for analytics, feature/memory pipelines and downstream subscribers.
 * Pass immutable event payloads. Registered event types bind to stable external names independent of Java class names.
 * Events are stamped with an id and occurrence timestamp at publish time, buffered in a bounded queue, and flushed to
 * {@link AiEventSink} in batches triggered by either {@code batchSize} or {@code linger} time on bounded on-demand
 * virtual threads (never pooled). Close after stopping game producers and before closing the game runtime.
 */
public final class AiEventPublisher implements AutoCloseable {
    public static final int DEFAULT_WORKERS = 1;
    public static final int DEFAULT_CAPACITY = 4096;
    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final Duration DEFAULT_LINGER = Duration.ofMillis(50);
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
    private final ArrayBlockingQueue<QueuedEvent> queue;
    private final Semaphore permits;
    private final int batchSize;
    private final long lingerNanos;
    private final long shutdownTimeoutNanos;
    private final ThreadFactory threads = Thread.ofVirtual().name("game-ai-event-", 0).factory();

    private final Set<Thread> activeWorkers = ConcurrentHashMap.newKeySet();
    private final Set<LingerSlot> lingerSlots = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object drainLock = new Object();
    private volatile boolean closed;
    private volatile boolean forceStopped;

    private final Meter queueGauge;
    private final Counter sent, lost, batches;

    private AiEventPublisher(Builder builder) {
        events = Map.copyOf(builder.events);
        json = builder.json;
        sink = builder.sink;
        idGenerator = builder.idGenerator;
        clock = builder.clock;
        queue = new ArrayBlockingQueue<>(builder.capacity);
        permits = new Semaphore(builder.workers);
        batchSize = builder.batchSize;
        lingerNanos = builder.linger.toNanos();
        shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();

        Metrics metrics = builder.metrics;
        queueGauge = metrics.gauge("managame.ai.events.queued", queue::size);
        sent = metrics.counter("managame.ai.events.sent");
        lost = metrics.counter("managame.ai.events.lost");
        batches = metrics.counter("managame.ai.events.batches");
        LOG.log(Level.INFO, "AI event publisher started with {0} event types", events.size());
    }

    public static Builder builder(AiEventSink sink) { return new Builder(sink); }

    /** Publish an unpartitioned event with empty metadata; returns false when dropped (closed or queue full). */
    public boolean publish(Object event) { return publish(null, Metadata.empty(), event); }

    /** Publish an event under {@code partitionKey} with empty metadata. */
    public boolean publish(String partitionKey, Object event) { return publish(partitionKey, Metadata.empty(), event); }

    /**
     * Enqueue an event for batched delivery and return immediately. Unregistered event classes are programming
     * errors ({@link IllegalArgumentException}). Dropped events are counted and logged. For events that must survive
     * a process crash, publish from a durable store or log pipeline.
     */
    public boolean publish(String partitionKey, Metadata metadata, Object event) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(event, "event");
        String name = events.get(event.getClass());
        if (name == null) throw new IllegalArgumentException("Unregistered event: " + event.getClass());
        if (closed) {
            lost(name, "AiEventPublisher is closed", null);
            return false;
        }
        String id = Objects.requireNonNull(idGenerator.get(), "idGenerator returned null");
        QueuedEvent item = new QueuedEvent(id, name, partitionKey, clock.getAsLong(), metadata, event);
        if (!queue.offer(item)) {
            lost(name, "event queue full", null);
            return false;
        }
        if (closed && queue.remove(item)) {
            lost(name, "AiEventPublisher is closed", null);
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
            List<QueuedEvent> raw = new ArrayList<>(Math.min(batchSize, 64));
            List<AiEvent> batch = new ArrayList<>(Math.min(batchSize, 64));
            while (!forceStopped) {
                raw.clear();
                QueuedEvent first = queue.poll();
                if (first == null) break;
                raw.add(first);
                drainReady(raw);
                if (raw.size() < batchSize && lingerNanos > 0 && !closed && !forceStopped) {
                    lingerForMore(raw, slot);
                }
                batch.clear();
                for (QueuedEvent item : raw) {
                    try {
                        batch.add(new AiEvent(item.eventId(), item.event(), item.partitionKey(),
                                item.occurredAt(), item.metadata(), json.encode(item.payload())));
                    } catch (Throwable failure) {
                        lost(item.event(), "encode failed", failure);
                    }
                }
                if (batch.isEmpty()) continue;
                try {
                    sink.send(List.copyOf(batch));
                    sent.increment(batch.size());
                    batches.increment();
                } catch (Throwable failure) {
                    lost.increment(batch.size());
                    LOG.log(Level.ERROR, "Lost " + batch.size() + " AI event(s) starting with " + batch.getFirst().event(), failure);
                }
            }
        } finally {
            lingerSlots.remove(slot);
            activeWorkers.remove(self);
            permits.release();
            if (!forceStopped && !queue.isEmpty()) maybeStartWorker();
            finishFlight();
        }
    }

    private void drainReady(List<QueuedEvent> raw) {
        while (raw.size() < batchSize) {
            QueuedEvent next = queue.poll();
            if (next == null) break;
            raw.add(next);
        }
    }

    private void lingerForMore(List<QueuedEvent> raw, LingerSlot slot) {
        long deadline = System.nanoTime() + lingerNanos;
        while (raw.size() < batchSize && !closed && !forceStopped) {
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
            raw.add(next);
            drainReady(raw);
        }
    }

    private void lost(String name, String reason, Throwable failure) {
        lost.increment();
        if (failure == null) LOG.log(Level.ERROR, "Lost AI event {0}: {1}", name, reason);
        else LOG.log(Level.ERROR, "Lost AI event " + name + ": " + reason, failure);
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
        for (QueuedEvent item; (item = queue.poll()) != null; dropped++) lost.increment();
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
        private Metrics metrics = Metrics.noop();
        private int workers = DEFAULT_WORKERS;
        private int capacity = DEFAULT_CAPACITY;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private Duration linger = DEFAULT_LINGER;
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

        public Builder metrics(Metrics metrics) { this.metrics = Objects.requireNonNull(metrics, "metrics"); return this; }

        public Builder limits(int workers, int capacity) {
            return limits(workers, capacity, batchSize, linger);
        }

        /**
         * Configure worker concurrency, bounded queue capacity, max batch size and linger window.
         * {@code linger} may be {@link Duration#ZERO} to flush immediately without waiting for more items.
         */
        public Builder limits(int workers, int capacity, int batchSize, Duration linger) {
            if (workers < 1 || capacity < 1 || batchSize < 1 || linger == null || linger.isNegative())
                throw new IllegalArgumentException("Invalid event publisher limits");
            this.workers = workers;
            this.capacity = capacity;
            this.batchSize = batchSize;
            this.linger = linger;
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
