package cn.managame.docdb.mongodb;

import cn.managame.core.mapping.DefaultValueConverter;
import cn.managame.core.mapping.ValueConverter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.Date;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

/** BSON field and parameter representations, independent of JDBC types. */
public final class MongoValueConverter implements ValueConverter {
    private final DefaultValueConverter scalars = new DefaultValueConverter();

    @Override public void validate(Class<?> type) {
        if (type == Instant.class || type == LocalDateTime.class || type == LocalDate.class) return;
        scalars.validate(type);
    }

    @Override public Object toStore(Object value, Class<?> declaredType) {
        if (value instanceof Instant instant) return Date.from(instant);
        if (value instanceof LocalDateTime time) {
            return Date.from(time.atZone(ZoneId.systemDefault()).withLaterOffsetAtOverlap().toInstant());
        }
        if (value instanceof LocalDate date) return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
        if (value instanceof BigInteger integer) return new Decimal128(new BigDecimal(integer));
        if (value instanceof BigDecimal decimal) return new Decimal128(decimal);
        return scalars.toStore(value, declaredType);
    }

    @Override public Object fromStore(Object value, Class<?> targetType) {
        if (value instanceof Date date) {
            Instant instant = Instant.ofEpochMilli(date.getTime());
            if (targetType == Instant.class) return instant;
            if (targetType == LocalDateTime.class) return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            if (targetType == LocalDate.class) return instant.atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (targetType == byte[].class && value instanceof Binary binary) return binary.getData();
        if (value instanceof Decimal128 decimal && (targetType == BigDecimal.class || targetType == BigInteger.class)) {
            value = decimal.bigDecimalValue();
        }
        return scalars.fromStore(value, targetType);
    }
}
