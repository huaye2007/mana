package cn.managame.runtime.internal;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CronScheduleTest {
    @Test void cronParsesAndCalculatesLeapDateAndRejectsInvalidExpressions() {
        var cron=new CronSchedule("0 0 0 29 2 ?",ZoneOffset.UTC);
        assertEquals(Instant.parse("2028-02-29T00:00:00Z"),cron.next(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-01-01T00:00:10Z"),new CronSchedule("*/5 * * * * ?",ZoneOffset.UTC).next(Instant.parse("2026-01-01T00:00:05Z")));
        assertThrows(IllegalArgumentException.class,() -> new CronSchedule("60 * * * * ?",ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class,() -> new CronSchedule("*/0 * * * * ?",ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class,() -> new CronSchedule("*-5 * * * * ?",ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class,() -> new CronSchedule("0 0 0 31 2 ?",ZoneOffset.UTC).next(Instant.now()));
        // Unix would OR the two day fields and Quartz rejects the pair; never silently AND them.
        assertThrows(IllegalArgumentException.class,() -> new CronSchedule("0 0 0 1 * 2",ZoneOffset.UTC));
        assertEquals(Instant.parse("2026-01-05T00:00:00Z"),new CronSchedule("0 0 0 * * 2",ZoneOffset.UTC).next(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"),new CronSchedule("0 0 0 1 * ?",ZoneOffset.UTC).next(Instant.parse("2026-01-01T00:00:00Z")));
    }
}
