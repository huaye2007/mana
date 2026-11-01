package cn.managame.runtime.time;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The game's wall clock and time zone, set in one place for the whole process: business code reads dates and times
 * here, and cron timers fire on it, finding their next time again within a second of a change to the clock or zone.
 * Delays, periodic timers and timeouts use the monotonic clock instead and do not move with it.
 */
public final class GameTime {
    private static volatile Clock clock = Clock.systemDefaultZone();

    private GameTime() {}

    public static long currentTimeMillis() {
        return clock.millis();
    }

    /** The current date and time in the game's zone. */
    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** The game's time zone, in which dates and cron expressions are read; the system's unless set. */
    public static ZoneId zone() {
        return clock.getZone();
    }

    /** The clock itself, whose zone is the game's. */
    public static Clock clock() {
        return clock;
    }

    /** Replaces the clock, for tests or to shift the game's time; its zone becomes the game's. */
    public static void setClock(Clock newClock) {
        clock = Objects.requireNonNull(newClock, "newClock");
    }

    /** Sets the game's time zone, once at startup as a rule, keeping the clock's time. */
    public static void setZone(ZoneId zone) {
        clock = clock.withZone(Objects.requireNonNull(zone, "zone"));
    }

    /** Restores the system clock, keeping the game's zone. */
    public static void resetClock() {
        clock = Clock.system(clock.getZone());
    }
}
