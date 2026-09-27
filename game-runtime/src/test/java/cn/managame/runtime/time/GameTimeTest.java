package cn.managame.runtime.time;

import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("GameTime")
class GameTimeTest {
    @AfterEach void resetClock() { GameTime.resetClock(); }

    @Test void fixedClockUsesExplicitZoneAndRejectsNullWithoutChangingClock() {
        Instant instant = Instant.parse("2026-09-25T00:30:00Z");
        GameTime.setClock(Clock.fixed(instant, ZoneId.of("America/New_York")));
        assertEquals(instant.toEpochMilli(), GameTime.currentTimeMillis());
        assertEquals(LocalDateTime.of(2026, 9, 25, 8, 30), GameTime.now(ZoneId.of("Asia/Shanghai")));
        assertEquals(LocalDateTime.of(2026, 9, 25, 0, 30), GameTime.now(ZoneOffset.UTC));
        assertThrows(NullPointerException.class, () -> GameTime.setClock(null));
        assertThrows(NullPointerException.class, () -> GameTime.now(null));
        assertEquals(instant.toEpochMilli(), GameTime.currentTimeMillis());
    }

    @Test void replacementIsVisibleAcrossThreadsAndResetRestoresSystemClock() throws Exception {
        GameTime.setClock(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertEquals(0, executor.submit(GameTime::currentTimeMillis).get(5, TimeUnit.SECONDS));
            GameTime.setClock(Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
            assertEquals(1234, executor.submit(GameTime::currentTimeMillis).get(5, TimeUnit.SECONDS));
        }
        GameTime.resetClock();
        assertTrue(Math.abs(System.currentTimeMillis() - GameTime.currentTimeMillis()) < 1000);
    }
}

