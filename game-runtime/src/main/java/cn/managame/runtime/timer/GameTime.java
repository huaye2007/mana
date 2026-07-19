package cn.managame.runtime.timer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Shared time source for timer scheduling and cron calculation.
 *
 * <p>The system UTC clock is used by default. Hosts may install an offset, fixed, or custom
 * thread-safe {@link Clock} without changing the operating-system clock.</p>
 */
public final class GameTime {

    private static final Clock SYSTEM_CLOCK = Clock.systemUTC();

    private static volatile Clock clock = SYSTEM_CLOCK;

    private GameTime() {
    }

    /** Returns the current timer time in epoch milliseconds. */
    public static long currentTimeMillis() {
        return clock.millis();
    }

    /** Returns the current timer time in the requested time zone. */
    public static LocalDateTime now(ZoneId zoneId) {
        return LocalDateTime.now(clock.withZone(Objects.requireNonNull(zoneId, "zoneId")));
    }

    /**
     * Replaces the shared timer clock. The supplied clock must be safe for concurrent access.
     */
    public static void setClock(Clock newClock) {
        clock = Objects.requireNonNull(newClock, "newClock");
    }

    /** Restores the default system UTC clock. */
    public static void resetClock() {
        clock = SYSTEM_CLOCK;
    }
}
