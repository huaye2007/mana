package cn.managame.runtime.internal;

import cn.managame.runtime.timer.Cron;

import java.time.*;
import java.util.*;
/** Six fields: second minute hour day-of-month month day-of-week (1=Sunday).
 * Supports *, ?, lists, ranges and steps. Zone is explicit; no catch-up execution. */
final class CronSchedule {
    private final BitSet[] fields = new BitSet[6];
    private static final int[] MIN = {0,0,0,1,1,1}, MAX = {59,59,23,31,12,7};
    private final ZoneId zone;
    CronSchedule(String expression, ZoneId zone) {
        this.zone = Objects.requireNonNull(zone);
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 6) throw new IllegalArgumentException("Cron needs six fields");
        for (int i = 0; i < 6; i++) {
            BitSet values = fields[i] = new BitSet(MAX[i] + 1);
            String part = parts[i];
            if (part.equals("?")) {
                if (i != 3 && i != 5) throw new IllegalArgumentException("? only allowed for day fields");
                part = "*";
            }
            for (String segment : part.split(",", -1)) {
                String[] stepParts = segment.split("/", -1);
                if (stepParts.length > 2) throw new IllegalArgumentException("Invalid cron step");
                int step = stepParts.length == 2 ? Integer.parseInt(stepParts[1]) : 1;
                if (step < 1 || step > MAX[i] + 1) throw new IllegalArgumentException("Invalid cron step");
                String[] range = stepParts[0].split("-", -1);
                if (range.length > 2 || range.length == 2 && range[0].equals("*"))
                    throw new IllegalArgumentException("Invalid cron range");
                int lo, hi;
                if (range[0].equals("*")) { lo = MIN[i]; hi = MAX[i]; }
                else { lo = Integer.parseInt(range[0]); hi = range.length == 2 ? Integer.parseInt(range[1]) : stepParts.length == 2 ? MAX[i] : lo; }
                if (lo < MIN[i] || hi > MAX[i] || lo > hi) throw new IllegalArgumentException("Cron field out of range");
                for (int n = lo; n <= hi; n += step) values.set(n);
            }
        }
    }
    Instant next(Instant after) {
        ZonedDateTime t = after.atZone(zone).plusSeconds(1).withNano(0);
        int endYear = t.getYear() + 8;
        while (t.getYear() <= endYear) {
            if (!fields[4].get(t.getMonthValue())) { t = t.plusMonths(1).withDayOfMonth(1).toLocalDate().atStartOfDay(zone); continue; }
            int dow = t.getDayOfWeek().getValue() % 7 + 1;
            if (!fields[3].get(t.getDayOfMonth()) || !fields[5].get(dow)) { t = t.toLocalDate().plusDays(1).atStartOfDay(zone); continue; }
            if (!fields[2].get(t.getHour())) { t = t.plusHours(1).withMinute(0).withSecond(0); continue; }
            if (!fields[1].get(t.getMinute())) { t = t.plusMinutes(1).withSecond(0); continue; }
            if (!fields[0].get(t.getSecond())) { t = t.plusSeconds(1); continue; }
            return t.toInstant();
        }
        throw new IllegalArgumentException("Cron has no occurrence in the next eight years");
    }
}
