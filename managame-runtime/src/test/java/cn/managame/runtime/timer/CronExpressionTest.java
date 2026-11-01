package cn.managame.runtime.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CronExpressionTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai"), PARIS = ZoneId.of("Europe/Paris");

    /** The first time after {@code after} that {@code expression} matches, both in Shanghai. */
    private static LocalDateTime next(String expression, String after) { return next(expression, SHANGHAI, after); }

    private static LocalDateTime next(String expression, ZoneId zone, String after) {
        long millis = LocalDateTime.parse(after).atZone(zone).toInstant().toEpochMilli();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(CronExpression.parse(expression).next(millis, zone)), zone);
    }

    private static LocalDateTime at(String time) { return LocalDateTime.parse(time); }

    @Test void findsTheNextMatchingSecondStrictlyAfter() {
        assertEquals(at("2026-11-01T05:00:00"), next("0 0 5 * * *", "2026-11-01T04:59:59"));
        assertEquals(at("2026-11-02T05:00:00"), next("0 0 5 * * *", "2026-11-01T05:00:00"), "not the time itself");
        assertEquals(at("2026-11-01T10:00:01"), next("* * * * * *", "2026-11-01T10:00:00.500"));
    }

    @Test void readsValuesRangesListsAndSteps() {
        // every quarter hour from nine to five on weekdays: Friday evening goes on to Monday morning
        assertEquals(at("2026-11-09T09:00:00"), next("0 */15 9-17 * * MON-FRI", "2026-11-06T17:50:00"));
        assertEquals(at("2026-11-01T12:25:10"), next("10,20 5/20 * * * *", "2026-11-01T12:05:20"));
        assertEquals(at("2026-11-01T12:00:30"), next("30-40/5 * * * * ?", "2026-11-01T12:00:00"));
    }

    @Test void walksPastShortMonthsAndToTheNextLeapDay() {
        assertEquals(at("2026-05-31T00:00:00"), next("0 0 0 31 * *", "2026-04-15T00:00:00"));
        assertEquals(at("2028-02-29T00:00:00"), next("0 0 0 29 2 *", "2026-03-01T00:00:00"));
    }

    @Test void aDayMatchingEitherTheDayOfMonthOrTheDayOfWeekFiresWhenBothAreGiven() {
        assertEquals(at("2026-11-02T00:00:00"), next("0 0 0 1 * MON", "2026-11-01T00:00:00"), "Monday the 2nd");
        assertEquals(at("2026-12-01T00:00:00"), next("0 0 0 1 * MON", "2026-11-30T00:00:00"), "Tuesday the 1st");
        assertEquals(at("2026-11-08T00:00:00"), next("0 0 0 * * 7", "2026-11-02T00:00:00"), "7 is Sunday");
        assertEquals(at("2027-01-03T00:00:00"), next("0 0 0 ? jan sun", "2026-11-01T00:00:00"), "names in any case");
    }

    @Test void aTimeDaylightSavingSkipsDoesNotFireAndOneItRepeatsFiresOnce() {
        // Paris moves from 02:00 to 03:00 on 29 March 2026, and from 03:00 back to 02:00 on 25 October 2026
        assertEquals(at("2026-03-30T02:30:00"), next("0 30 2 * * *", PARIS, "2026-03-28T03:00:00"));
        CronExpression halfPastTwo = CronExpression.parse("0 30 2 * * *");
        long first = halfPastTwo.next(LocalDateTime.parse("2026-10-25T01:00:00").atZone(PARIS).toInstant().toEpochMilli(), PARIS);
        assertEquals(ZoneOffset.ofHours(2), PARIS.getRules().getOffset(Instant.ofEpochMilli(first)), "the first 02:30");
        assertEquals(at("2026-10-26T02:30:00"), LocalDateTime.ofInstant(Instant.ofEpochMilli(halfPastTwo.next(first, PARIS)), PARIS));
    }

    @Test void refusesMalformedExpressionsAndOnesThatNeverMatch() {
        for (String bad : new String[] {"0 0 5 * *", "60 * * * * *", "0 0 24 * * *", "0 0 0 0 * *", "0 0 0 * 13 *",
                "0 0 0 * * 8", "0 0 0 * * FUN", "0 0 0 * * 5-1", "*/0 * * * * *", "0 0 0 , * *", "0 0 0 30 2 *"})
            assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(bad), bad);
    }
}
