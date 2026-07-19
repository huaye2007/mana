package cn.managame.runtime.timer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameTimeTest {

    @AfterEach
    void resetClock() {
        GameTime.resetClock();
    }

    @Test
    void externalClockControlsTimerTimeWithoutChangingSystemTime() {
        Instant instant = Instant.parse("2030-01-02T03:04:05Z");
        GameTime.setClock(Clock.fixed(instant, ZoneOffset.UTC));

        assertEquals(instant.toEpochMilli(), GameTime.currentTimeMillis());
        assertEquals(LocalDateTime.of(2030, 1, 2, 3, 4, 5), GameTime.now(ZoneOffset.UTC));
        assertEquals(1_000,
                new CronExpression("6 4 3 2 1 *", ZoneOffset.UTC).nextDelayMs());
    }

    @Test
    void clockCannotBeNull() {
        assertThrows(NullPointerException.class, () -> GameTime.setClock(null));
    }
}
