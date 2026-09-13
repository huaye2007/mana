package cn.managame.runtime.clock;

import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
public final class MutableGameClock implements GameClock {
    private record Reading(Instant wall, long nanos) {}
    private final AtomicReference<Reading> time;
    private final ZoneId zone;
    public MutableGameClock(Instant time, ZoneId zone) {
        this.time = new AtomicReference<>(new Reading(Objects.requireNonNull(time), 0)); this.zone = Objects.requireNonNull(zone);
    }
    public Instant now() { return time.get().wall(); }
    public long nanoTime() { return time.get().nanos(); }
    public ZoneId zoneId() { return zone; }
    /** Adjust calendar time only. */
    public void setTime(Instant value) {
        Objects.requireNonNull(value);
        time.updateAndGet(old -> new Reading(value, old.nanos()));
    }
    /** Positive advances move both clocks; negative advances adjust only the calendar. */
    public Instant advance(Duration duration) {
        long elapsed = duration.isNegative() ? 0 : duration.toNanos();
        return time.updateAndGet(old -> new Reading(old.wall().plus(duration), old.nanos() + elapsed)).wall();
    }
    /** Advance elapsed time independently for deterministic relative-timer tests. */
    public void advanceElapsed(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Negative elapsed advance");
        long nanos = duration.toNanos();
        time.updateAndGet(old -> new Reading(old.wall(), old.nanos() + nanos));
    }
}
