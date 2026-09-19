package cn.managame.rdb;

import cn.managame.core.mapping.DefaultValueConverter;
import cn.managame.core.mapping.ValueConverter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.*;

/** JDBC field and parameter representations. */
public final class RdbValueConverter implements ValueConverter {
    private final DefaultValueConverter scalars = new DefaultValueConverter();

    @Override public void validate(Class<?> type) {
        if (type == LocalTime.class || type == Instant.class || type == LocalDateTime.class || type == LocalDate.class) return;
        scalars.validate(type);
    }

    @Override public Object toStore(Object value, Class<?> declaredType) {
        if (value instanceof Instant instant) return Timestamp.from(instant);
        if (value instanceof LocalDateTime time) return Timestamp.valueOf(time);
        if (value instanceof LocalDate date) return java.sql.Date.valueOf(date);
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        return scalars.toStore(value, declaredType);
    }

    @Override public Object fromStore(Object value, Class<?> targetType) {
        if (targetType == LocalTime.class && value instanceof java.sql.Time time) {
            return time.toLocalTime().withNano((int) Math.floorMod(time.getTime(), 1000L) * 1_000_000);
        }
        if (targetType == Instant.class && value instanceof java.util.Date date) {
            return value instanceof Timestamp time ? time.toInstant() : Instant.ofEpochMilli(date.getTime());
        }
        if (targetType == LocalDateTime.class) {
            if (value instanceof Timestamp time) return time.toLocalDateTime();
            if (value instanceof java.util.Date date) return LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
        }
        if (targetType == LocalDate.class) {
            if (value instanceof java.sql.Date date) return date.toLocalDate();
            if (value instanceof java.util.Date date) return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return scalars.fromStore(value, targetType);
    }
}
