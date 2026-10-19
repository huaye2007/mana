package cn.managame.runtime.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Publication slots for the Route tasks currently running, so a reading can name a Route that is stuck
 * rather than only count one that is busy.
 *
 * <p>A task takes one slot of a fixed array when it enters its Route and clears it when it leaves, so the
 * cost is one compare-and-set on a slot chosen by thread rather than an insertion into a structure every
 * worker shares. Each slot holds an immutable triple, so a reader can never observe a Domain from one task
 * with the Key of another.
 *
 * <p>The array is deliberately finite, and a task that finds its probes taken runs untracked. That makes
 * the reading a lower bound: what it names was really running and its age is never overstated, but under
 * sustained saturation it can miss one. The trade is the right way round, because a stuck task holds its
 * slot for as long as it is stuck while healthy ones give theirs back at once, so the slots drift toward
 * exactly the tasks worth reporting.
 */
final class RunningTasks {
    /** Enough for the concurrent tasks of a busy instance; one reference each, so a couple of KB. */
    private static final int SLOTS = 256;
    private static final int MASK = SLOTS - 1;
    /** How far a task looks for a free slot before giving up and running untracked. */
    private static final int PROBES = 8;
    /** Returned by {@link #claim} when no slot was free; {@link #release} ignores it. */
    static final int UNTRACKED = -1;

    /** One running task. Immutable, so a reader always sees one task's whole identity or none of it. */
    record Running(int routeDomain, long routeKey, long startedAt) {}

    private final AtomicReferenceArray<Running> slots = new AtomicReferenceArray<>(SLOTS);

    /** Publishes this task if a slot is free, and returns the slot to hand back to {@link #release}. */
    int claim(int routeDomain, long routeKey, long startedAt) {
        // Thread id rather than a shared counter: spreading the probes must not reintroduce the contended
        // word this whole structure exists to avoid.
        int probe = (int) Thread.currentThread().threadId();
        Running running = null;
        for (int i = 0; i < PROBES; i++) {
            int slot = (probe + i) & MASK;
            if (slots.get(slot) != null) continue;
            if (running == null) running = new Running(routeDomain, routeKey, startedAt);
            if (slots.compareAndSet(slot, null, running)) return slot;
        }
        return UNTRACKED;
    }

    void release(int slot) {
        if (slot != UNTRACKED) slots.set(slot, null);
    }

    /**
     * The oldest running task of each Domain, from one pass. Domains with nothing running are absent, so an
     * empty result means nothing tracked is running at all.
     */
    Map<Integer, Running> oldestByDomain() {
        Map<Integer, Running> oldest = new HashMap<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            Running running = slots.get(slot);
            if (running == null) continue;
            oldest.merge(running.routeDomain(), running,
                    (existing, candidate) -> existing.startedAt() <= candidate.startedAt() ? existing : candidate);
        }
        return oldest;
    }
}
