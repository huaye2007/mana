package cn.managame.runtime.timer;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CronExpressionTest {

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("60 * * * * *"));
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("* * * * 13 *"));
    }

    @Test
    void rejectsInvalidStepsAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("*/0 * * * * *"));
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("10-5 * * * * *"));
    }

    @Test
    void dayOfMonthAndDayOfWeekUseOrSemantics() {
        // "0 0 0 29 * 1" = 每月 29 号 或 每周一 的 00:00:00（标准 cron 是 OR，不是 AND）。
        // 2026-01-06 是周二，下一个周一 01-12 早于 29 号，OR 语义应取 01-12（6 天后）。
        long delay = new CronExpression("0 0 0 29 * 1", ZoneOffset.UTC)
                .nextDelayMs(LocalDateTime.of(2026, 1, 6, 0, 0, 0));
        assertEquals(6L * 24 * 3600 * 1000, delay, "日与周同时限制时应为 OR：下一个周一即触发");
    }

    @Test
    void zoneIdDecidesWhichLocalMidnightFires() {
        // 同一物理时刻，UTC 与 UTC+8 距各自"每天 12 点"的延迟应相差 8 小时（模 24）
        long delayUtc = new CronExpression("0 0 12 * * *", ZoneOffset.UTC).nextDelayMs();
        long delayPlus8 = new CronExpression("0 0 12 * * *", ZoneOffset.ofHours(8)).nextDelayMs();
        long dayMs = 24 * 3600 * 1000L;
        long diff = Math.floorMod(delayUtc - delayPlus8, dayMs);
        // 两次 nextDelayMs 取 now 有先后，允许秒级误差
        assertEquals(8 * 3600 * 1000L, Math.round(diff / 1000.0) * 1000L,
                "时区差 8 小时应体现在触发延迟上");
    }
}
