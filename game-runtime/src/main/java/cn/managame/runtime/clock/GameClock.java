package cn.managame.runtime.clock;

import java.time.*;
public interface GameClock {
    Instant now();
    ZoneId zoneId();
    /** Monotonic time, independent of calendar adjustments. Only differences are meaningful. */
    default long nanoTime() { return System.nanoTime(); }
    default long nowMillis() { return now().toEpochMilli(); }
    static GameClock system(ZoneId zone) {
        Clock clock = Clock.system(zone);
        return new GameClock() {
            public Instant now() { return clock.instant(); }
            public ZoneId zoneId() { return clock.getZone(); }
        };
    }
}
