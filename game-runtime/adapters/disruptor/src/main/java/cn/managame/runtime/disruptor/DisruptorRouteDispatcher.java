package cn.managame.runtime.disruptor;

import com.lmax.disruptor.EventHandler;

import cn.managame.runtime.route.Route;
import cn.managame.runtime.execution.RouteDispatcher;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional fixed-key platform-thread backend. Runtime owns entity FIFO and batch size. */
public final class DisruptorRouteDispatcher implements RouteDispatcher {
    private static final class Slot { Runnable batch; }
    private static final EventTranslatorOneArg<Slot, Runnable> TRANSLATOR =
            (slot, sequence, batch) -> slot.batch = batch;
    private final List<Partition> partitions = new ArrayList<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger(), running = new AtomicInteger();

    private final class Partition implements EventHandler<Slot> {
        final Disruptor<Slot> disruptor;
        final RingBuffer<Slot> ring;
        // Consumer-owned; at most one ready continuation per admitted entity.
        final ArrayDeque<Runnable> ready = new ArrayDeque<>();
        Sequence consumed;
        volatile Thread owner;

        Partition(String threadName, int ringSize) {
            ThreadFactory factory = runnable -> {
                Thread thread = Thread.ofPlatform().name(threadName).unstarted(runnable);
                threads.add(thread); return thread;
            };
            disruptor = new Disruptor<>(Slot::new, ringSize, factory,
                    ProducerType.MULTI, new BlockingWaitStrategy());
            ring = disruptor.getRingBuffer();
            disruptor.handleEventsWith(this);
        }

        @Override public void setSequenceCallback(Sequence sequence) { consumed = sequence; }
        @Override public void onStart() { owner = Thread.currentThread(); }

        @Override public void onEvent(Slot slot, long sequence, boolean endOfBatch) {
            ready.addLast(slot.batch);
            slot.batch = null;
            // The runnable is now local. Release the ring slot before executing business:
            // a perpetually active entity must not hold ingress capacity while taking turns.
            consumed.set(sequence);
            do {
                Runnable batch = ready.removeFirst();
                pending.decrementAndGet(); running.incrementAndGet();
                try { batch.run(); }
                catch (Throwable error) {
                    System.getLogger(DisruptorRouteDispatcher.class.getName())
                            .log(System.Logger.Level.ERROR, "Route batch escaped exception handling", error);
                } finally { running.decrementAndGet(); }
                // Admit newly published entities between turns; otherwise keep draining
                // continuations instead of returning to the ring's blocking wait.
            } while (endOfBatch && !ready.isEmpty() && !ring.isAvailable(sequence + 1));
        }
    }

    public DisruptorRouteDispatcher(String name, int workers, int ringSize) {
        Objects.requireNonNull(name);
        if (name.isBlank() || workers < 1 || ringSize < 2 || Integer.bitCount(ringSize) != 1)
            throw new IllegalArgumentException("Require name, positive workers and power-of-two ring size >= 2");
        try {
            for (int i = 0; i < workers; i++) {
                Partition partition = new Partition("game-" + name + "-disruptor-" + i, ringSize);
                partitions.add(partition);
                partition.disruptor.start();
            }
        } catch (RuntimeException | Error failure) {
            shutdown();
            throw failure;
        }
    }

    public void dispatch(Route route, Runnable batch) {
        Objects.requireNonNull(route); Objects.requireNonNull(batch);
        if (stopped.get()) throw new RejectedExecutionException("Disruptor dispatcher is stopped");
        int index = partitionIndex(route);
        pending.incrementAndGet();
        boolean accepted = false;
        try {
            accepted = partitions.get(index).ring.tryPublishEvent(TRANSLATOR, batch);
            if (!accepted) throw new RejectedExecutionException("Disruptor partition is full: " + index);
        } finally { if (!accepted) pending.decrementAndGet(); }
    }

    public void reschedule(Route route, Runnable batch) {
        Objects.requireNonNull(route); Objects.requireNonNull(batch);
        if (stopped.get()) throw new RejectedExecutionException("Disruptor dispatcher is stopped");
        Partition partition = partitions.get(partitionIndex(route));
        if (partition.owner != Thread.currentThread())
            throw new IllegalStateException("Continuation must be submitted by its partition consumer");
        partition.ready.addLast(batch);
        pending.incrementAndGet();
    }

    private int partitionIndex(Route route) {
        long key = route.key();
        long hash = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdL;
        hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (int) Long.remainderUnsigned(hash, partitions.size());
    }

    /** Runtime calls this only when all accepted entity queues have drained. */
    public void shutdown() {
        if (stopped.compareAndSet(false, true)) partitions.forEach(partition -> partition.disruptor.halt());
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative shutdown timeout");
        long budget;
        try { budget = timeout.toNanos(); } catch (ArithmeticException error) { budget = Long.MAX_VALUE; }
        long start = System.nanoTime();
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                long remaining = Math.max(0, budget - (System.nanoTime() - start));
                if (remaining == 0) return false;
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            }
        }
        return true;
    }

    public int runningBatches() { return running.get(); }
    public int readyRoutes() { return pending.get(); }
}
