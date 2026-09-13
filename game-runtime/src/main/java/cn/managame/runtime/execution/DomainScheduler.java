package cn.managame.runtime.execution;

import cn.managame.runtime.route.Route;

import java.util.ArrayDeque;
import java.time.Duration;
import java.util.concurrent.*;

/** Platform workers use their executor queue directly; virtual batches have a concurrency gate. */
final class DomainScheduler implements RouteDispatcher {
    private final ExecutorService[] executors;
    private final VirtualBatches virtual;
    private final boolean affinity;

    DomainScheduler(ExecutionDomain configuration) {
        affinity = configuration.scheduling() == ExecutionDomain.Scheduling.KEY_AFFINITY;
        int count = configuration.concurrency();
        String prefix = "game-" + configuration.name() + "-";
        if (configuration.mode() == ExecutionDomain.Mode.VIRTUAL) {
            var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
            executors = new ExecutorService[] { executor };
            virtual = new VirtualBatches(executor, count);
        } else {
            virtual = null;
            executors = new ExecutorService[affinity ? count : 1];
            for (int i = 0; i < executors.length; i++) {
                int concurrency = affinity ? 1 : count;
                String workerPrefix = affinity ? prefix + "partition-" + i + "-" : prefix;
                // Runtime admission bounds the number of queued entity batches.
                executors[i] = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(), Thread.ofPlatform().name(workerPrefix, 0).factory());
            }
        }
    }

    public void dispatch(Route route, Runnable batch) {
        if (virtual != null) virtual.execute(batch);
        else executors[affinity ? partition(route.key(), executors.length) : 0].execute(batch);
    }
    public void reschedule(Route route, Runnable batch) { dispatch(route, batch); }

    private static int partition(long key, int count) {
        long hash = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdL;
        hash = (hash ^ (hash >>> 33)) * 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (int) Long.remainderUnsigned(hash, count);
    }

    public int runningBatches() {
        if (virtual != null) return virtual.runningBatches();
        int total = 0;
        for (ExecutorService executor : executors) total += ((ThreadPoolExecutor) executor).getActiveCount();
        return total;
    }
    public int readyRoutes() {
        if (virtual != null) return virtual.readyRoutes();
        int total = 0;
        for (ExecutorService executor : executors) total += ((ThreadPoolExecutor) executor).getQueue().size();
        return total;
    }
    public void shutdown() { for (ExecutorService executor : executors) executor.shutdown(); }
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        ShutdownDeadline deadline = new ShutdownDeadline(timeout);
        for (ExecutorService executor : executors)
            if (!executor.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) return false;
        return true;
    }

    /** Queued entities wait here without creating parked virtual threads. */
    private static final class VirtualBatches {
        private final ExecutorService executor;
        private final int concurrency;
        private final ArrayDeque<Runnable> ready = new ArrayDeque<>();
        private int running;

        VirtualBatches(ExecutorService executor, int concurrency) {
            this.executor = executor; this.concurrency = concurrency;
        }
        synchronized void execute(Runnable batch) {
            if (running < concurrency) {
                running++;
                try { launch(batch); }
                catch (RuntimeException | Error error) { running--; throw error; }
            } else ready.addLast(batch);
        }
        private void launch(Runnable batch) {
            executor.execute(() -> {
                try { batch.run(); }
                finally { finished(); }
            });
        }
        private synchronized void finished() {
            Runnable next = ready.pollFirst();
            if (next == null) running--;
            else launch(next);
        }
        synchronized int runningBatches() { return running; }
        synchronized int readyRoutes() { return ready.size(); }
    }
}
