package cn.managame.runtime.internal;

import cn.managame.runtime.timer.Cron;

import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.time.GameTime;
import cn.managame.runtime.timer.CronScheduler;
import cn.managame.runtime.timer.TimerRef;
import cn.managame.runtime.internal.RuntimeCompiler.CronBinding;
import cn.managame.runtime.internal.RuntimeCompiler.CronKey;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import static cn.managame.core.FrameworkErrorCodes.RUNTIME_CLOSED;

/** Cron is a cancellable chain of one-shot RuntimeTimers, with no clock listener. */
final class DefaultCronScheduler implements CronScheduler, AutoCloseable {
    private static final class Entry {
        final CronBinding binding;
        long generation;
        boolean active;
        TimerRef timer;
        Entry(CronBinding binding) { this.binding = binding; }
    }

    private final Map<CronKey, Entry> entries;
    private final RuntimeTimers timers;
    private final BiConsumer<CronBinding, Throwable> errors;
    private volatile boolean closed;

    DefaultCronScheduler(List<CronBinding> bindings, RuntimeTimers timers,
                         BiConsumer<CronBinding, Throwable> errors) {
        Map<CronKey, Entry> map = new LinkedHashMap<>();
        for (CronBinding binding : bindings) map.put(binding.id(), new Entry(binding));
        entries = Collections.unmodifiableMap(map);
        this.timers = timers;
        this.errors = errors;
    }

    void start() { rescheduleAll(); }

    public boolean cancel(Class<?> type, String methodName) {
        Entry entry = entries.get(key(type, methodName));
        if (entry == null) return false;
        synchronized (entry) {
            if (!entry.active) return false;
            stop(entry);
            return true;
        }
    }

    public boolean reschedule(Class<?> type, String methodName) {
        CronKey id = key(type, methodName);
        requireOpen();
        Entry entry = entries.get(id);
        if (entry == null) return false;
        restart(entry);
        return true;
    }

    public void rescheduleAll() {
        requireOpen();
        for (Entry entry : entries.values()) restart(entry);
    }

    private void restart(Entry entry) {
        synchronized (entry) {
            requireOpen();
            stop(entry);
            entry.active = true;
            try { arm(entry, entry.generation); }
            catch (RuntimeException | Error e) { stop(entry); throw e; }
        }
    }

    // Called under entry's monitor. No user method executes while holding it.
    private void arm(Entry entry, long generation) {
        Instant now = Instant.ofEpochMilli(GameTime.currentTimeMillis());
        Duration delay = Duration.between(now, entry.binding.schedule().next(now));
        entry.timer = timers.schedule(entry.binding.domain(), entry.binding.key(), delay,
            () -> fire(entry, generation), () -> advance(entry, generation));
    }

    private void fire(Entry entry, long generation) {
        synchronized (entry) {
            if (!current(entry, generation)) return;
            // This invocation has claimed execution. Cancellation only stops future invocations.
            entry.timer = null;
        }
        try { entry.binding.invoke(); }
        catch (Throwable e) { errors.accept(entry.binding, e); }
        finally { advance(entry, generation); }
    }

    private void advance(Entry entry, long generation) {
        Throwable failure = null;
        synchronized (entry) {
            if (!current(entry, generation)) return;
            try { arm(entry, generation); }
            catch (RuntimeException | Error e) {
                stop(entry);
                if (!closed && !(e instanceof RuntimeDispatchException rejected
                        && rejected.errorCode() == RUNTIME_CLOSED)) failure = e;
            }
        }
        if (failure != null) errors.accept(entry.binding, failure);
    }

    private boolean current(Entry entry, long generation) {
        return !closed && entry.active && entry.generation == generation;
    }

    private static void stop(Entry entry) {
        entry.active = false;
        entry.generation++;
        if (entry.timer != null) {
            entry.timer.cancel();
            entry.timer = null;
        }
    }

    private static CronKey key(Class<?> type, String methodName) {
        return new CronKey(Objects.requireNonNull(type, "type"),
            Objects.requireNonNull(methodName, "methodName"));
    }

    private void requireOpen() {
        if (closed) throw new RuntimeDispatchException(RUNTIME_CLOSED, "Runtime cron scheduler is closed");
    }

    public void close() {
        closed = true;
        for (Entry entry : entries.values()) {
            synchronized (entry) { stop(entry); }
        }
    }
}
