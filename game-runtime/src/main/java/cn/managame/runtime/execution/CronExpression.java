package cn.managame.runtime.execution;


import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
/**
 * Six fields: second minute hour day-of-month month day-of-week.
 * Supports *, ?, lists, inclusive ranges, steps and JAN..DEC / SUN..SAT.
 * One day field must be ?. Quartz-specific L, W, # and year fields are rejected.
 */
public final class CronExpression {
    private final BitSet seconds, minutes, hours, days, months, weekdays;
    private final String expression;
    private CronExpression(String text) {
        expression = Objects.requireNonNull(text);
        String[] fields = text.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (fields.length != 6) throw new IllegalArgumentException("Cron requires six fields: " + text);
        boolean anyDay = fields[3].equals("?");
        boolean anyWeekday = fields[5].equals("?");
        if (anyDay == anyWeekday) throw new IllegalArgumentException("Exactly one cron day field must be ?");
        seconds = field(fields[0], 0, 59, List.of());
        minutes = field(fields[1], 0, 59, List.of());
        hours = field(fields[2], 0, 23, List.of());
        days = field(anyDay ? "*" : fields[3], 1, 31, List.of());
        months = field(fields[4], 1, 12, List.of("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"));
        weekdays = field(anyWeekday ? "*" : fields[5], 1, 7, List.of("SUN","MON","TUE","WED","THU","FRI","SAT"));
        // Check calendar feasibility independently of the runtime clock.
        boolean possible = false;
        for (LocalDate date = LocalDate.of(2000, 1, 1); date.getYear() < 2400; date = date.plusDays(1)) {
            if (matchesDate(date)) { possible = true; break; }
        }
        if (!possible) throw new IllegalArgumentException("Cron has no matching date: " + text);
    }
    public static CronExpression parse(String text) { return new CronExpression(text); }
    public Instant nextAfter(Instant instant, ZoneId zone) {
        ZonedDateTime candidate = instant.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1).atZone(zone);
        int limit = candidate.getYear() + 8;
        while (candidate.getYear() <= limit) {
            if (!matchesDate(candidate.toLocalDate())) {
                candidate = candidate.toLocalDate().plusDays(1).atStartOfDay(zone); continue;
            }
            // Advance along the instant timeline inside a day, retaining both sides of DST overlaps.
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.plusSeconds(60 - candidate.getSecond()); continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.plusSeconds(60 - candidate.getSecond()); continue;
            }
            if (!seconds.get(candidate.getSecond())) {
                int next = seconds.nextSetBit(candidate.getSecond() + 1);
                candidate = candidate.plusSeconds(next < 0 ? 60 - candidate.getSecond() : next - candidate.getSecond()); continue;
            }
            return candidate.toInstant();
        }
        throw new IllegalArgumentException("Cron has no occurrence in the next eight years: " + expression);
    }
    private boolean matchesDate(LocalDate date) {
        int weekday = date.getDayOfWeek().getValue() % 7 + 1;
        return months.get(date.getMonthValue()) && days.get(date.getDayOfMonth()) && weekdays.get(weekday);
    }
    private static BitSet field(String text, int min, int max, List<String> names) {
        BitSet values = new BitSet(max + 1);
        for (String part : text.split(",", -1)) {
            String[] stepParts = part.split("/", -1);
            if (stepParts.length > 2) throw new IllegalArgumentException("Invalid cron field: " + text);
            int step = stepParts.length == 2 ? number(stepParts[1], List.of()) : 1;
            if (step < 1 || step > max - min + 1) throw new IllegalArgumentException("Invalid cron step: " + text);
            String range = stepParts[0];
            int start, end;
            if (range.equals("*")) { start = min; end = max; }
            else {
                String[] bounds = range.split("-", -1);
                if (bounds.length > 2) throw new IllegalArgumentException("Invalid cron range: " + text);
                start = number(bounds[0], names);
                end = bounds.length == 2 ? number(bounds[1], names) : stepParts.length == 2 ? max : start;
            }
            if (start < min || end > max || start > end) throw new IllegalArgumentException("Cron field out of range: " + text);
            for (int value = start; value <= end; value += step) values.set(value);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Empty cron field");
        return values;
    }
    private static int number(String text, List<String> names) {
        int named = names.indexOf(text);
        if (named >= 0) return named + 1;
        if (!text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid cron value: " + text);
        try { return Integer.parseInt(text); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid cron number: " + text, error); }
    }
    @Override public String toString() { return expression; }
}
