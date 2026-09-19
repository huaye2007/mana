package cn.managame.core.log;

import cn.managame.annotation.LogPartition;
import cn.managame.core.DataException;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.PropertyMetadata;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Bound once per repository; append only reads its prebound event-time accessor. */
final class LogPartitioner {
    private final PropertyMetadata time;
    private final String prefix;
    private final ZoneId zone;
    private final DateTimeFormatter format;

    private LogPartitioner(PropertyMetadata time, String table, LogPartition rule) {
        this.time = time;
        this.prefix = table + "_";
        Class<?> type = time.type();
        if (type != LocalDate.class && type != LocalDateTime.class && type != Instant.class
                && type != long.class && type != Long.class) {
            throw new DataException("@LogPartition requires LocalDate, LocalDateTime, Instant or epoch-millisecond long/Long: " + time.field());
        }
        try { this.zone = ZoneId.of(rule.zone()); }
        catch (DateTimeException error) { throw new DataException("Invalid @LogPartition zone: " + rule.zone(), error); }
        this.format = DateTimeFormatter.ofPattern(switch (rule.value()) {
            case DAY -> "uuuuMMdd";
            case MONTH -> "uuuuMM";
            case YEAR -> "uuuu";
        });
    }

    static LogPartitioner inspect(EntityMetadata<?> metadata, String defaultName) {
        LogPartitioner partitioner = null;
        for (Class<?> type = metadata.type(); type != Object.class && type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                LogPartition rule = field.getAnnotation(LogPartition.class);
                if (rule == null) continue;
                if (partitioner != null) throw new DataException("Only one @LogPartition field is allowed: " + metadata.type().getName());
                PropertyMetadata property = metadata.properties().stream().filter(p -> p.field().equals(field)).findFirst()
                        .orElseThrow(() -> new DataException("@LogPartition must be a mapped instance field: " + field));
                partitioner = new LogPartitioner(property, defaultName, rule);
            }
        }
        return partitioner;
    }

    String table(Object log) {
        Object value = time.get(log);
        if (value == null) throw new DataException("@LogPartition time must not be null: " + time.field());
        LocalDate date;
        if (value instanceof LocalDate local) date = local;
        else if (value instanceof LocalDateTime local) date = local.toLocalDate();
        else {
            Instant instant = value instanceof Instant time ? time : Instant.ofEpochMilli((Long) value);
            date = instant.atZone(zone).toLocalDate();
        }
        return prefix + date.format(format);
    }
}
