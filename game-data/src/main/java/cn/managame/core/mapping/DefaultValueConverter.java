package cn.managame.core.mapping;

import java.math.*;
import java.util.UUID;

/** Backend-neutral scalar conversion; storage representations belong to the adapters. */
public final class DefaultValueConverter implements ValueConverter {
    @Override public void validate(Class<?> type) {
        Class<?> boxed = box(type);
        if (boxed == String.class || boxed == Boolean.class || boxed == Character.class
                || boxed == Byte.class || boxed == Short.class || boxed == Integer.class || boxed == Long.class
                || boxed == Float.class || boxed == Double.class || boxed == BigInteger.class
                || boxed == BigDecimal.class || boxed == UUID.class || boxed == byte[].class || boxed.isEnum()) return;
        throw new cn.managame.core.DataException("Unsupported field type: " + type.getName());
    }

    @Override
    public Object toStore(Object value, Class<?> declaredType) {
        if (value == null) return null;
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Character c) return c.toString();
        if (value instanceof UUID u) return u.toString();
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Object fromStore(Object value, Class<?> targetType) {
        if (value == null) return primitiveDefault(targetType);
        Class<?> boxed = box(targetType);
        if (boxed.isInstance(value)) return value;
        if (boxed.isEnum()) return Enum.valueOf((Class<? extends Enum>) boxed, value.toString());
        if (boxed == UUID.class) return UUID.fromString(value.toString());
        if (Number.class.isAssignableFrom(boxed) && value instanceof Number n) {
            if (boxed == Integer.class) return n.intValue();
            if (boxed == Long.class) return n.longValue();
            if (boxed == Short.class) return n.shortValue();
            if (boxed == Byte.class) return n.byteValue();
            if (boxed == Float.class) return n.floatValue();
            if (boxed == Double.class) return n.doubleValue();
            if (boxed == BigInteger.class) return decimal(n).toBigIntegerExact();
            if (boxed == BigDecimal.class) return decimal(n);
        }
        if (boxed == Boolean.class && value instanceof Number n) return n.intValue() != 0;
        if (boxed == Character.class) return value.toString().charAt(0);
        if (boxed == String.class) return value.toString();
        return value;
    }

    /** Preserve decimal digits instead of routing arbitrary-precision values through long/double. */
    private static BigDecimal decimal(Number value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        return new BigDecimal(value.toString());
    }

    private static Object primitiveDefault(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == char.class) return '\0';
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == boolean.class) return Boolean.class;
        if (t == char.class) return Character.class;
        return t;
    }
}
