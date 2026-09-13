package cn.managame.runtime.execution;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.Metadata;
import cn.managame.runtime.diagnostics.HandlerException;
import cn.managame.runtime.diagnostics.RuntimeClosedException;
import cn.managame.runtime.diagnostics.RuntimeOverloadedException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Queue mutation is short and separate from cron evaluation, dispatch and error observation. */
final class GameScheduler implements AutoCloseable {
    private final GameRuntime runtime;
    private final TimerOptions options;
    private final Object queueLock = new Object();
    private final NavigableSet<TimerTask> queue = new TreeSet<>(
            Comparator.comparing((TimerTask t) -> t.due).thenComparingLong(t -> t.sequence));
    private final NavigableSet<TimerTask> relativeQueue = new TreeSet<>(
            Comparator.comparing((TimerTask t) -> t.due).thenComparingLong(t -> t.sequence));
    private final long elapsedOrigin;
    private final ScheduledExecutorService ticker;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final LongAdder rejections = new LongAdder();
    private long sequence;
    // Includes a reserved slot for each cron while its next occurrence is being calculated.
    private int scheduled;
    private boolean closed;
    private boolean automatic;
    private ScheduledFuture<?> pollTask;

    GameScheduler(GameRuntime runtime, TimerOptions options) {
        this.runtime = runtime; this.options = Objects.requireNonNull(options);
        this.elapsedOrigin = runtime.clock().nanoTime();
        // Read the externally supplied clock before acquiring the poller's resources.
        this.ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("game-runtime-clock").factory());
    }

    void start(boolean automatic) {
        synchronized (queueLock) { this.automatic = automatic; updatePolling(); }
    }

    // Invoked only under queueLock. Entity task dispatch never needs this clock.
    private void updatePolling() {
        if (closed || !automatic || scheduled == 0) {
            if (pollTask != null) { pollTask.cancel(false); pollTask = null; }
        } else if (pollTask == null) {
            pollTask = ticker.scheduleWithFixedDelay(this::safePoll, 0, 10, TimeUnit.MILLISECONDS);
        }
    }
    boolean automaticPollingActive() { synchronized (queueLock) { return pollTask != null; } }
    private void safePoll() {
        try { runDue(); }
        catch (Throwable error) { runtime.report("scheduler", null, error); }
    }

    TimerTask schedule(Instant deadline, Runnable dispatch) {
        return add(deadline, deadline, false, dispatch, null, false, null);
    }

    private Instant elapsedNow() {
        return Instant.EPOCH.plusNanos(runtime.clock().nanoTime() - elapsedOrigin);
    }

    TimerTask schedule(Instant deadline, Runnable dispatch, RouteTask completion) {
        return add(deadline, deadline, false, dispatch, null, false, completion);
    }

    TimerTask schedule(Duration delay, Runnable dispatch, RouteTask completion) {
        return add(runtime.clock().now().plus(delay), elapsedNow().plus(delay), true, dispatch, null, false, completion);
    }

    private TimerTask add(Instant deadline, Instant due, boolean relative, Runnable dispatch,
                          HandlerBindings.CronBinding cron, boolean reserved, RouteTask completion) {
        synchronized (queueLock) {
            if (closed) {
                rejections.increment();
                throw new RuntimeClosedException();
            }
            if (!reserved && scheduled >= options.maxTimers()) {
                rejections.increment();
                throw new RuntimeOverloadedException(RuntimeOverloadedException.Reason.TIMER_CAPACITY);
            }
            TimerTask task = new TimerTask(deadline, due, relative, sequence++, dispatch, this::remove, cron, completion);
            (relative ? relativeQueue : queue).add(task);
            if (!reserved) scheduled++;
            updatePolling();
            return task;
        }
    }

    private void remove(TimerTask task) {
        synchronized (queueLock) {
            if ((task.relative ? relativeQueue : queue).remove(task)) scheduled--;
            updatePolling();
        }
    }

    void cron(HandlerBindings.CronBinding binding) { armCron(binding, false); }

    private void armCron(HandlerBindings.CronBinding binding, boolean reserved) {
        Instant next = binding.expression().nextAfter(runtime.clock().now(), runtime.clock().zoneId());
        add(next, next, false, () -> runtime.dispatch(new HandlerContext(binding.route(), Metadata.empty()),
                binding.name(), binding::invoke), binding, reserved, null);
    }

    int runDue() {
        // Concurrent/reentrant polling never blocks handlers behind a scheduler observer.
        if (!polling.compareAndSet(false, true)) return 0;
        try {
            List<TimerTask> ready = new ArrayList<>();
            Instant now = runtime.clock().now();
            Instant elapsed = elapsedNow();
            synchronized (queueLock) {
                if (closed) return 0;
                while (ready.size() < options.maxTimersPerPoll()) {
                    boolean wallDue = !queue.isEmpty() && !queue.first().due.isAfter(now);
                    boolean elapsedDue = !relativeQueue.isEmpty() && !relativeQueue.first().due.isAfter(elapsed);
                    if (!wallDue && !elapsedDue) break;
                    boolean useRelative = elapsedDue && !wallDue;
                    if (wallDue && elapsedDue) {
                        int age = Duration.between(relativeQueue.first().due, elapsed)
                                .compareTo(Duration.between(queue.first().due, now));
                        useRelative = age > 0 || (age == 0 && relativeQueue.first().sequence < queue.first().sequence);
                    }
                    TimerTask timer = (useRelative ? relativeQueue : queue).pollFirst();
                    if (timer.claim()) {
                        if (timer.cron == null) scheduled--;
                        ready.add(timer);
                    } else {
                        scheduled--;
                    }
                }
            }
            for (TimerTask timer : ready) {
                if (timer.cron != null) {
                    try { armCron(timer.cron, true); }
                    catch (Throwable error) {
                        synchronized (queueLock) { if (!closed) scheduled--; }
                        runtime.report("cron scheduling", null, error);
                    }
                }
                try { timer.dispatch.run(); timer.dispatched(); }
                catch (Throwable error) {
                    HandlerException failure = new HandlerException("timer dispatch", null, error);
                    timer.rejected(failure);
                    runtime.report("timer dispatch", null, failure);
                }
            }
            return ready.size();
        } finally {
            polling.set(false);
            synchronized (queueLock) { updatePolling(); }
        }
    }

    int scheduledTimers() { synchronized (queueLock) { return scheduled; } }
    long rejectedTimers() { return rejections.sum(); }

    @Override public void close() {
        List<TimerTask> pending;
        synchronized (queueLock) {
            if (closed) return;
            closed = true;
            updatePolling();
            pending = new ArrayList<>(queue);
            pending.addAll(relativeQueue);
            queue.clear();
            relativeQueue.clear();
            scheduled = 0;
        }
        ticker.shutdownNow();
        pending.forEach(TimerTask::cancel);
    }
}
