package cn.managame.runtime.time;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Process-wide business wall clock. Replacing it never reschedules timers.
 * Elapsed time, network timeouts and delay timers must use a monotonic clock.
 */
public final class GameTime {
    private static final Clock SYSTEM_CLOCK = Clock.systemUTC();
    private static volatile Clock clock = SYSTEM_CLOCK;

    private GameTime() {}

    public static long currentTimeMillis() {
        return clock.millis();
    }

    public static LocalDateTime now(ZoneId zoneId) {
        return LocalDateTime.now(clock.withZone(Objects.requireNonNull(zoneId, "zoneId")));
    }

    public static void setClock(Clock newClock) {
        clock = Objects.requireNonNull(newClock, "newClock");
    }

    public static void resetClock() {
        clock = SYSTEM_CLOCK;
    }
}
