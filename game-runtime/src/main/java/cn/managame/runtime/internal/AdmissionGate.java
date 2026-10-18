package cn.managame.runtime.internal;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gate every unit of Route work passes through, and the counter shutdown drains.
 *
 * <p>One CAS word holds both: the sign bit says admission has stopped, the low 63 bits count work that
 * was admitted and has not finished. They are fused deliberately. With a separate flag and counter, a
 * submitter could read "still accepting"; shutdown could then stop admission, see a count of zero and
 * release the drain; and the submitter could increment afterwards — leaving a task about to run after
 * {@code awaitTermination} already reported success.
 */
final class AdmissionGate {
    private static final long STOPPED = Long.MIN_VALUE;
    private final AtomicLong state = new AtomicLong();
    private final CountDownLatch drained = new CountDownLatch(1);

    boolean acquire(boolean continuation) {
        long current;
        do {
            current = state.get();
            if (current < 0 && (!continuation || (current & Long.MAX_VALUE) == 0)) return false;
        } while (!state.compareAndSet(current, current + 1));
        return true;
    }
    void release() {
        if (state.decrementAndGet() == STOPPED) drained.countDown();
    }
    void stop() {
        long previous = state.getAndUpdate(value -> value | STOPPED);
        if ((previous & Long.MAX_VALUE) == 0) drained.countDown();
    }
    boolean accepting() { return state.get() >= 0; }
    long count() { return state.get() & Long.MAX_VALUE; }
    boolean await(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative drain timeout");
        return drained.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
