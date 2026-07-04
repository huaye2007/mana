package cn.managame.runtime.timer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.BitSet;

/**
 * 简易 cron 表达式解析器。支持 6 位格式：秒 分 时 日 月 周。
 * <p>
 * 支持的语法：
 * <ul>
 *   <li>* - 任意值</li>
 *   <li>数字 - 固定值</li>
 *   <li>a-b - 范围</li>
 *   <li>a,b,c - 列表</li>
 *   <li>&#42;/n - 步长</li>
 * </ul>
 * </p>
 *
 * <p>"日"(day-of-month)与"周"(day-of-week)采用标准 cron 的 <b>OR</b> 语义：两者都被限制
 * （都不是 {@code *}）时，任一匹配即触发；只限制其中一个时，另一个按 {@code *} 处理。</p>
 *
 * <p>不支持的高级语法（{@code ? L W # } 及月份/星期英文名）需要时另行扩展。</p>
 */
public class CronExpression {

    private final BitSet seconds = new BitSet(60);
    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32); // 1-31
    private final BitSet months = new BitSet(13);       // 1-12
    private final BitSet daysOfWeek = new BitSet(8);    // 0-7 (0和7都是周日)
    /** 日/周字段是否被限制（非 *），决定两者是 OR 还是各自按 * 处理 */
    private final boolean daysOfMonthRestricted;
    private final boolean daysOfWeekRestricted;
    private final ZoneId zoneId;

    public CronExpression(String expression) {
        this(expression, ZoneId.systemDefault());
    }

    /**
     * @param zoneId cron 表达式按哪个时区解释（"每天 0 点"是哪里的 0 点）
     */
    public CronExpression(String expression, ZoneId zoneId) {
        if (zoneId == null) {
            throw new IllegalArgumentException("zoneId must not be null");
        }
        this.zoneId = zoneId;
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Cron expression must have 6 fields: " + expression);
        }
        parseField(parts[0], seconds, 0, 59);
        parseField(parts[1], minutes, 0, 59);
        parseField(parts[2], hours, 0, 23);
        parseField(parts[3], daysOfMonth, 1, 31);
        parseField(parts[4], months, 1, 12);
        parseField(parts[5], daysOfWeek, 0, 7);
        this.daysOfMonthRestricted = !parts[3].equals("*");
        this.daysOfWeekRestricted = !parts[5].equals("*");
    }

    /**
     * 以表达式时区的当前时间计算距下一次触发的毫秒数。2 年内无匹配返回 -1。
     */
    public long nextDelayMs() {
        return nextDelayMs(LocalDateTime.now(zoneId));
    }

    /**
     * 计算从 now（按表达式时区解释）开始的下一次触发时间，返回距离 now 的毫秒数。
     * 如果在 2 年内找不到匹配时间，返回 -1。
     */
    public long nextDelayMs(LocalDateTime now) {
        LocalDateTime next = nextTime(now.plusSeconds(1));
        if (next == null) {
            return -1;
        }
        long nowMs = now.atZone(zoneId).toInstant().toEpochMilli();
        long nextMs = next.atZone(zoneId).toInstant().toEpochMilli();
        return nextMs - nowMs;
    }

    private LocalDateTime nextTime(LocalDateTime from) {
        LocalDateTime dt = from.withNano(0);
        // 最多搜索 2 年
        LocalDateTime limit = dt.plusYears(2);

        while (dt.isBefore(limit)) {
            if (!months.get(dt.getMonthValue())) {
                dt = dt.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            boolean domMatch = daysOfMonth.get(dt.getDayOfMonth());
            int dow = dt.getDayOfWeek().getValue() % 7; // Monday=1..Sunday=0
            boolean dowMatch = daysOfWeek.get(dow) || daysOfWeek.get(dow == 0 ? 7 : dow);
            // 标准 cron：日/周都被限制时取 OR，否则未限制的那个等价于 *（恒真），用 AND 即可。
            boolean dayMatch = (daysOfMonthRestricted && daysOfWeekRestricted)
                    ? (domMatch || dowMatch)
                    : (domMatch && dowMatch);
            if (!dayMatch) {
                dt = dt.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!hours.get(dt.getHour())) {
                dt = dt.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes.get(dt.getMinute())) {
                dt = dt.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!seconds.get(dt.getSecond())) {
                dt = dt.plusSeconds(1);
                continue;
            }
            return dt;
        }
        return null;
    }

    private void parseField(String field, BitSet bits, int min, int max) {
        for (String part : field.split(",")) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Empty cron field segment in: " + field);
            }
            if (part.contains("/")) {
                String[] split = part.split("/");
                if (split.length != 2 || split[1].isBlank()) {
                    throw new IllegalArgumentException("Invalid cron step segment: " + part);
                }
                int step = Integer.parseInt(split[1]);
                if (step <= 0) {
                    throw new IllegalArgumentException("Cron step must be positive: " + part);
                }
                Range range = parseRange(split[0], min, max);
                for (int i = range.from(); i <= range.to(); i += step) {
                    bits.set(i);
                }
            } else if (part.contains("-")) {
                Range range = parseRange(part, min, max);
                for (int i = range.from(); i <= range.to(); i++) {
                    bits.set(i);
                }
            } else if (part.equals("*")) {
                for (int i = min; i <= max; i++) {
                    bits.set(i);
                }
            } else {
                bits.set(parseValue(part, min, max));
            }
        }
    }

    private Range parseRange(String expression, int min, int max) {
        if (expression.equals("*")) {
            return new Range(min, max);
        }
        if (!expression.contains("-")) {
            int value = parseValue(expression, min, max);
            return new Range(value, max);
        }
        String[] range = expression.split("-");
        if (range.length != 2 || range[0].isBlank() || range[1].isBlank()) {
            throw new IllegalArgumentException("Invalid cron range segment: " + expression);
        }
        int from = parseValue(range[0], min, max);
        int to = parseValue(range[1], min, max);
        if (from > to) {
            throw new IllegalArgumentException("Cron range start must be <= end: " + expression);
        }
        return new Range(from, to);
    }

    private int parseValue(String value, int min, int max) {
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Cron value out of range [" + min + "," + max + "]: " + parsed);
        }
        return parsed;
    }

    private record Range(int from, int to) {
    }
}
