package cn.managame.runtime.timer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * A parsed cron expression of six fields, second minute hour day-of-month month day-of-week; see
 * {@link GameTimer#cron(cn.managame.runtime.Context, String, cn.managame.runtime.GameTask)} for the syntax.
 * Each field is a bit set of the values it allows. Immutable.
 */
final class CronExpression {
    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    /** How far ahead a next time is looked for; enough for February 29 across a century year that is not leap. */
    private static final int SEARCH_YEARS = 10;
    private final String text;
    private final long seconds, minutes, hours, daysOfMonth, months, daysOfWeek;
    /** Whether the day-of-month or day-of-week field is {@code *} or {@code ?}, which leaves the day to the other. */
    private final boolean anyDayOfMonth, anyDayOfWeek;

    private CronExpression(String text, String[] fields) {
        this.text = text;
        seconds = field(fields[0], 0, 59, null);
        minutes = field(fields[1], 0, 59, null);
        hours = field(fields[2], 0, 23, null);
        daysOfMonth = field(fields[3], 1, 31, null);
        months = field(fields[4], 1, 12, MONTHS);
        long week = field(fields[5], 0, 7, DAYS);
        daysOfWeek = (week | week >>> 7) & 0x7F;   // 7 is Sunday as 0 is
        anyDayOfMonth = isAny(fields[3]);
        anyDayOfWeek = isAny(fields[5]);
        if (anyDayOfWeek && !anyDayOfMonth && !someMonthHasADay())
            throw new IllegalArgumentException("Cron expression never matches: " + text);
    }

    /** Throws IllegalArgumentException for an expression that is malformed or names no day that exists. */
    static CronExpression parse(String text) {
        String[] fields = text.trim().split("\\s+");
        if (fields.length != 6)
            throw new IllegalArgumentException("Cron expression needs six fields, second minute hour day-of-month month day-of-week: " + text);
        return new CronExpression(text, fields);
    }

    /**
     * The first time after {@code afterMillis} that matches in {@code zone}, in epoch milliseconds, at the start of its second. A local
     * time that daylight saving skips does not match; one it repeats matches once, on the offset {@code afterMillis}
     * is in when that is one of the two.
     */
    long next(long afterMillis, ZoneId zone) {
        ZonedDateTime after = Instant.ofEpochMilli(afterMillis).atZone(zone);
        LocalDateTime t = after.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        int lastYear = t.getYear() + SEARCH_YEARS;
        while (t.getYear() <= lastYear) {
            if (!has(months, t.getMonthValue())) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
                continue;
            }
            int hour = matchesDay(t.toLocalDate()) ? nextIn(hours, t.getHour()) : -1;
            if (hour < 0) {
                t = t.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            if (hour != t.getHour()) t = t.withHour(hour).withMinute(0).withSecond(0);
            int minute = nextIn(minutes, t.getMinute());
            if (minute < 0) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                continue;
            }
            if (minute != t.getMinute()) t = t.withMinute(minute).withSecond(0);
            int second = nextIn(seconds, t.getSecond());
            if (second < 0) {
                t = t.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
                continue;
            }
            t = t.withSecond(second);
            if (zone.getRules().getValidOffsets(t).isEmpty()) {   // skipped by daylight saving
                t = t.plusSeconds(1);
                continue;
            }
            return ZonedDateTime.ofLocal(t, zone, after.getOffset()).toInstant().toEpochMilli();
        }
        throw new IllegalStateException("Cron expression matched nothing in " + SEARCH_YEARS + " years: " + text);
    }

    @Override public String toString() { return text; }

    private boolean matchesDay(LocalDate date) {
        boolean ofMonth = has(daysOfMonth, date.getDayOfMonth()), ofWeek = has(daysOfWeek, date.getDayOfWeek().getValue() % 7);
        return anyDayOfMonth ? ofWeek : anyDayOfWeek ? ofMonth : ofMonth || ofWeek;
    }

    private boolean someMonthHasADay() {
        for (Month month : Month.values())
            if (has(months, month.getValue()) && nextIn(daysOfMonth, 1) <= month.maxLength()) return true;
        return false;
    }

    private static boolean has(long bits, int value) { return (bits >>> value & 1) != 0; }

    /** The smallest value at least {@code from} in {@code bits}, or -1. */
    private static int nextIn(long bits, int from) {
        long rest = bits & -1L << from;
        return rest == 0 ? -1 : Long.numberOfTrailingZeros(rest);
    }

    private static boolean isAny(String field) { return field.equals("*") || field.equals("?"); }

    private long field(String field, int min, int max, List<String> names) {
        long bits = 0;
        for (String part : field.split(",", -1)) {
            int slash = part.indexOf('/');
            String range = slash < 0 ? part : part.substring(0, slash);
            int step = slash < 0 ? 1 : number(part.substring(slash + 1), 1, max, null, field);
            int from, to;
            if (isAny(range)) {
                from = min;
                to = max;
            } else {
                int dash = range.indexOf('-');
                from = number(dash < 0 ? range : range.substring(0, dash), min, max, names, field);
                to = dash >= 0 ? number(range.substring(dash + 1), min, max, names, field) : slash < 0 ? from : max;
                if (from > to) throw invalid(field, from + " is after " + to);
            }
            for (int value = from; value <= to; value += step) bits |= 1L << value;
        }
        return bits;
    }

    private int number(String text, int min, int max, List<String> names, String field) {
        int index = names == null ? -1 : names.indexOf(text.toUpperCase(Locale.ROOT));
        if (index >= 0) return names == MONTHS ? index + 1 : index;
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw invalid(field, "'" + text + "' is not a value from " + min + " to " + max);
    }

    private IllegalArgumentException invalid(String field, String problem) {
        return new IllegalArgumentException("Cron expression " + text + ", field " + field + ": " + problem);
    }
}
