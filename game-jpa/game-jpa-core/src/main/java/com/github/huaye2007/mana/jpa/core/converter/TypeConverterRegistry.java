package com.github.huaye2007.mana.jpa.core.converter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类型转换器注册中心。
 */
public class TypeConverterRegistry {

    private final Map<ConverterKey, TypeConverter<?, ?>> converters = new ConcurrentHashMap<>();
    private final Map<Class<?>, TypeConverter<?, ?>> writeConverters = new ConcurrentHashMap<>();

    public Object write(Object source) {
        if (source == null) {
            return null;
        }
        Optional<TypeConverter<Object, Object>> custom = findWrite(source.getClass());
        if (custom.isPresent()) {
            return custom.get().write(source);
        }
        if (source instanceof Enum<?> e) {
            return e.name();
        }
        if (source instanceof UUID uuid) {
            return uuid.toString();
        }
        if (source instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (source instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (source instanceof LocalDate localDate) {
            return Date.valueOf(localDate);
        }
        if (source instanceof LocalTime localTime) {
            return Time.valueOf(localTime);
        }
        if (source instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        return source;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public Object read(Object target, Class<?> sourceType) {
        if (target == null) {
            return null;
        }
        if (sourceType.isPrimitive()) {
            sourceType = primitiveWrapper(sourceType);
        }
        Optional<TypeConverter<Object, Object>> custom = findRead(sourceType, target.getClass());
        if (custom.isPresent()) {
            return custom.get().read(target);
        }
        if (sourceType.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) sourceType.asSubclass(Enum.class), target.toString());
        }
        if (sourceType == UUID.class) {
            return target instanceof UUID ? target : UUID.fromString(target.toString());
        }
        if (sourceType == Instant.class) {
            if (target instanceof Timestamp timestamp) {
                return timestamp.toInstant();
            }
            if (target instanceof java.util.Date date) {
                return date.toInstant();
            }
            return Instant.parse(target.toString());
        }
        if (sourceType == LocalDateTime.class) {
            if (target instanceof Timestamp timestamp) {
                return timestamp.toLocalDateTime();
            }
            if (target instanceof LocalDateTime localDateTime) {
                return localDateTime;
            }
            return LocalDateTime.parse(target.toString());
        }
        if (sourceType == LocalDate.class) {
            if (target instanceof Date date) {
                return date.toLocalDate();
            }
            if (target instanceof LocalDate localDate) {
                return localDate;
            }
            return LocalDate.parse(target.toString());
        }
        if (sourceType == LocalTime.class) {
            if (target instanceof Time time) {
                return time.toLocalTime();
            }
            if (target instanceof LocalTime localTime) {
                return localTime;
            }
            return LocalTime.parse(target.toString());
        }
        if (sourceType == BigInteger.class) {
            if (target instanceof BigDecimal bigDecimal) {
                return bigDecimal.toBigInteger();
            }
            if (target instanceof Number number) {
                return BigInteger.valueOf(number.longValue());
            }
            return new BigInteger(target.toString());
        }
        if (sourceType == BigDecimal.class) {
            if (target instanceof BigDecimal bigDecimal) {
                return bigDecimal;
            }
            return new BigDecimal(target.toString());
        }
        return target;
    }

    public void register(TypeConverter<?, ?> converter) {
        ConverterKey key = new ConverterKey(converter.sourceType(), converter.targetType());
        converters.put(key, converter);
        writeConverters.put(converter.sourceType(), converter);
    }

    @SuppressWarnings("unchecked")
    public <S, T> Optional<TypeConverter<S, T>> find(Class<S> sourceType, Class<T> targetType) {
        ConverterKey key = new ConverterKey(sourceType, targetType);
        return Optional.ofNullable((TypeConverter<S, T>) converters.get(key));
    }

    @SuppressWarnings("unchecked")
    public Optional<TypeConverter<Object, Object>> findWrite(Class<?> sourceType) {
        TypeConverter<?, ?> converter = writeConverters.get(sourceType);
        if (converter != null) {
            return Optional.of((TypeConverter<Object, Object>) converter);
        }
        for (Map.Entry<Class<?>, TypeConverter<?, ?>> entry : writeConverters.entrySet()) {
            if (entry.getKey().isAssignableFrom(sourceType)) {
                return Optional.of((TypeConverter<Object, Object>) entry.getValue());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public Optional<TypeConverter<Object, Object>> findRead(Class<?> sourceType, Class<?> targetType) {
        TypeConverter<?, ?> converter = converters.get(new ConverterKey(sourceType, targetType));
        if (converter != null) {
            return Optional.of((TypeConverter<Object, Object>) converter);
        }
        for (Map.Entry<ConverterKey, TypeConverter<?, ?>> entry : converters.entrySet()) {
            ConverterKey key = entry.getKey();
            if (key.source().isAssignableFrom(sourceType) && key.target().isAssignableFrom(targetType)) {
                return Optional.of((TypeConverter<Object, Object>) entry.getValue());
            }
        }
        return Optional.empty();
    }

    private static Class<?> primitiveWrapper(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private record ConverterKey(Class<?> source, Class<?> target) {}
}
