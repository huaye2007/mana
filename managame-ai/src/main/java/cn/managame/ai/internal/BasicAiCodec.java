package cn.managame.ai.internal;

import cn.managame.ai.AiCodec;
import cn.managame.ai.annotation.McpParam;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AiCodec#basic()}: encodes and decodes scalars, enums, collections, maps, arrays, {@link Optional} and Java
 * records (with {@link ClassValue}-cached {@link MethodHandle} accessors and constructors) with no third-party
 * dependency. Public only to be reachable across {@code managame-ai}'s packages; not API.
 */
public final class BasicAiCodec implements AiCodec {
    public static final BasicAiCodec INSTANCE = new BasicAiCodec();

    private static final ClassValue<RecordPlan> RECORD_PLANS = new ClassValue<>() {
        @Override protected RecordPlan computeValue(Class<?> recordClass) {
            return compileRecordPlan(recordClass);
        }
    };

    private BasicAiCodec() {}

    @Override public String encode(Object value) {
        return Json.write(value);
    }

    @Override public Object decode(String json, Type type) {
        Object parsed = Json.parse(json);
        return convert(parsed, type);
    }

    /** Whether {@link #INSTANCE} can decode values of {@code type} without a user-provided JSON library. */
    public static boolean supports(Type type) {
        if (type instanceof Class<?> raw) {
            if (isScalar(raw) || raw == Object.class || raw.isEnum()) return true;
            if (raw.isArray()) return supports(raw.getComponentType());
            if (raw.isRecord()) {
                for (RecordComponent component : raw.getRecordComponents()) {
                    if (!supports(component.getGenericType())) return false;
                }
                return true;
            }
            return List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)
                    || Collection.class == raw || Map.class.isAssignableFrom(raw);
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            Type[] args = pt.getActualTypeArguments();
            if (raw == Optional.class) return args.length == 1 && supports(args[0]);
            if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw) || Collection.class == raw)
                return args.length == 1 && supports(args[0]);
            if (Map.class.isAssignableFrom(raw))
                return args.length == 2 && isSupportedMapKey(args[0]) && supports(args[1]);
        }
        return false;
    }

    private static boolean isSupportedMapKey(Type keyType) {
        return keyType instanceof Class<?> k && (k == Object.class || isScalar(k) || k.isEnum());
    }

    /** Converts a value already parsed by {@link Json#parse} into {@code type}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convert(Object value, Type type) {
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            if (raw == Optional.class) {
                return value == null ? Optional.empty() : Optional.ofNullable(convert(value, pt.getActualTypeArguments()[0]));
            }
            if (value == null) return null;
            if (List.class.isAssignableFrom(raw) || Collection.class == raw) {
                return convertList(value, pt.getActualTypeArguments()[0]);
            }
            if (Set.class.isAssignableFrom(raw)) {
                return new LinkedHashSet<>(convertList(value, pt.getActualTypeArguments()[0]));
            }
            if (Map.class.isAssignableFrom(raw)) {
                Type keyType = pt.getActualTypeArguments()[0];
                if (!isSupportedMapKey(keyType))
                    throw new IllegalArgumentException("Unsupported Map key type " + keyType.getTypeName() + "; configure an AiCodec");
                return convertMap(value, keyType, pt.getActualTypeArguments()[1]);
            }
            throw new IllegalArgumentException("Unsupported generic type " + type.getTypeName() + "; configure an AiCodec");
        }
        if (!(type instanceof Class<?> raw)) {
            throw new IllegalArgumentException("Unsupported type " + type.getTypeName() + "; configure an AiCodec");
        }
        if (value == null) {
            if (raw.isPrimitive()) throw new IllegalArgumentException("Null value for primitive " + raw.getName());
            return null;
        }
        if (raw == Object.class) return value;
        if (raw == String.class) {
            if (value instanceof String s) return s;
            throw new IllegalArgumentException("Expected string, got " + value.getClass().getSimpleName());
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (value instanceof Boolean b) return b;
            throw new IllegalArgumentException("Expected boolean, got " + value);
        }
        if (raw == int.class || raw == Integer.class) {
            long v = toLong(value, raw);
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Out of range for int: " + value);
            }
            return (int) v;
        }
        if (raw == long.class || raw == Long.class) return toLong(value, raw);
        if (raw == short.class || raw == Short.class) {
            long v = toLong(value, raw);
            if (v < Short.MIN_VALUE || v > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Out of range for short: " + value);
            }
            return (short) v;
        }
        if (raw == byte.class || raw == Byte.class) {
            long v = toLong(value, raw);
            if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Out of range for byte: " + value);
            }
            return (byte) v;
        }
        if (raw == double.class || raw == Double.class) return toDouble(value);
        if (raw == float.class || raw == Float.class) return (float) toDouble(value);
        if (raw == BigInteger.class) return toBigInteger(value);
        if (raw == BigDecimal.class) return toBigDecimal(value);
        if (raw == char.class || raw == Character.class) {
            if (value instanceof String s && s.length() == 1) return s.charAt(0);
            throw new IllegalArgumentException("Expected single-character string, got " + value);
        }
        if (raw == UUID.class) {
            if (value instanceof String s) return UUID.fromString(s);
            throw new IllegalArgumentException("Expected UUID string, got " + value);
        }
        if (raw == URI.class) {
            if (value instanceof String s) return URI.create(s);
            throw new IllegalArgumentException("Expected URI string, got " + value);
        }
        if (raw == Instant.class) {
            if (value instanceof String s) return Instant.parse(s);
            throw new IllegalArgumentException("Expected Instant string, got " + value);
        }
        if (raw == LocalDate.class) {
            if (value instanceof String s) return LocalDate.parse(s);
            throw new IllegalArgumentException("Expected LocalDate string, got " + value);
        }
        if (raw == LocalTime.class) {
            if (value instanceof String s) return LocalTime.parse(s);
            throw new IllegalArgumentException("Expected LocalTime string, got " + value);
        }
        if (raw == LocalDateTime.class) {
            if (value instanceof String s) return LocalDateTime.parse(s);
            throw new IllegalArgumentException("Expected LocalDateTime string, got " + value);
        }
        if (raw == OffsetDateTime.class) {
            if (value instanceof String s) return OffsetDateTime.parse(s);
            throw new IllegalArgumentException("Expected OffsetDateTime string, got " + value);
        }
        if (raw == ZonedDateTime.class) {
            if (value instanceof String s) return ZonedDateTime.parse(s);
            throw new IllegalArgumentException("Expected ZonedDateTime string, got " + value);
        }
        if (raw == Duration.class) {
            if (value instanceof String s) return Duration.parse(s);
            throw new IllegalArgumentException("Expected ISO-8601 Duration string, got " + value);
        }
        if (raw.isEnum()) {
            if (value instanceof String s) return Enum.valueOf((Class<Enum>) raw, s);
            throw new IllegalArgumentException("Expected enum string for " + raw.getSimpleName() + ", got " + value);
        }
        if (raw.isArray()) {
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected JSON array for " + raw.getSimpleName());
            Class<?> component = raw.getComponentType();
            Object array = Array.newInstance(component, list.size());
            for (int i = 0; i < list.size(); i++) Array.set(array, i, convert(list.get(i), component));
            return array;
        }
        if (List.class.isAssignableFrom(raw) || Collection.class == raw) return convertList(value, Object.class);
        if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>(convertList(value, Object.class));
        if (Map.class.isAssignableFrom(raw)) return convertMap(value, String.class, Object.class);
        if (raw.isRecord()) return convertRecord(value, raw);
        throw new IllegalArgumentException("No basic decoding for " + raw.getName() + "; configure an AiCodec");
    }

    static RecordPlan recordPlan(Class<?> recordClass) {
        return RECORD_PLANS.get(recordClass);
    }

    private static List<Object> convertList(Object value, Type elementType) {
        if (!(value instanceof List<?> rawList)) throw new IllegalArgumentException("Expected JSON array");
        List<Object> out = new ArrayList<>(rawList.size());
        for (Object item : rawList) out.add(convert(item, elementType));
        return out;
    }

    private static Map<Object, Object> convertMap(Object value, Type keyType, Type valueType) {
        if (!(value instanceof Map<?, ?> rawMap)) throw new IllegalArgumentException("Expected JSON object");
        Map<Object, Object> out = new LinkedHashMap<>(rawMap.size());
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String keyStr = entry.getKey().toString();
            Object k = (keyType == String.class || keyType == Object.class) ? keyStr : convert(keyStr, keyType);
            out.put(k, convert(entry.getValue(), valueType));
        }
        return out;
    }

    private static Object convertRecord(Object value, Class<?> recordClass) {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected JSON object for " + recordClass.getSimpleName());
        RecordPlan plan = RECORD_PLANS.get(recordClass);
        ComponentPlan[] components = plan.components();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            ComponentPlan component = components[i];
            String fieldName = component.wireName();
            Object raw = map.get(fieldName);
            if (raw == null) {
                if (component.isOptional()) {
                    args[i] = Optional.empty();
                } else if (component.isPrimitive() || component.explicitRequired()) {
                    throw new IllegalArgumentException("Missing required field '" + fieldName + "'");
                } else {
                    args[i] = null;
                }
            } else {
                args[i] = convert(raw, component.genericType());
            }
        }
        return plan.construct(recordClass, args);
    }

    private static RecordPlan compileRecordPlan(Class<?> recordClass) {
        RecordComponent[] rawComponents = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[rawComponents.length];
        ComponentPlan[] plans = new ComponentPlan[rawComponents.length];
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            for (int i = 0; i < rawComponents.length; i++) {
                RecordComponent rc = rawComponents[i];
                paramTypes[i] = rc.getType();
                McpParam ann = rc.getAnnotation(McpParam.class);
                String wireName = ann != null && !ann.name().isEmpty() ? ann.name() : rc.getName();
                Method accessor = rc.getAccessor();
                accessor.setAccessible(true);
                MethodHandle getter = lookup.unreflect(accessor)
                        .asType(MethodType.methodType(Object.class, Object.class));
                plans[i] = new ComponentPlan(
                        wireName,
                        rc.getType(),
                        rc.getGenericType(),
                        rc.getType().isPrimitive(),
                        rc.getType() == Optional.class,
                        ann != null && ann.required(),
                        getter);
            }
            Constructor<?> ctor = recordClass.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            MethodHandle ctorHandle = lookup.unreflectConstructor(ctor)
                    .asSpreader(Object[].class, rawComponents.length)
                    .asType(MethodType.methodType(Object.class, Object[].class));
            return new RecordPlan(plans, ctorHandle);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot compile record plan for " + recordClass.getName(), e);
        }
    }

    private static long toLong(Object value, Class<?> target) {
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Short sh) return sh.longValue();
        if (value instanceof Byte b) return b.longValue();
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s.trim());
        }
        if (value instanceof BigInteger bi) {
            try {
                return bi.longValueExact();
            } catch (ArithmeticException outOfRange) {
                throw new IllegalArgumentException("Out of range for " + target.getSimpleName() + ": " + value, outOfRange);
            }
        }
        if (value instanceof Double d && Math.rint(d) == d && Double.isFinite(d)) {
            try {
                return BigDecimal.valueOf(d).longValueExact();
            } catch (ArithmeticException outOfRange) {
                throw new IllegalArgumentException("Out of range for " + target.getSimpleName() + ": " + value, outOfRange);
            }
        }
        throw new IllegalArgumentException("Expected integer for " + target.getSimpleName() + ", got " + value);
    }

    private static BigInteger toBigInteger(Object value) {
        if (value instanceof Long l) return BigInteger.valueOf(l);
        if (value instanceof Integer i) return BigInteger.valueOf(i);
        if (value instanceof BigInteger bi) return bi;
        if (value instanceof Double d && Math.rint(d) == d && Double.isFinite(d)) {
            return BigDecimal.valueOf(d).toBigIntegerExact();
        }
        if (value instanceof String s && !s.isBlank()) {
            return new BigInteger(s.trim());
        }
        throw new IllegalArgumentException("Expected integer for BigInteger, got " + value);
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d)) return d;
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.trim());
                if (Double.isFinite(d)) return d;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new IllegalArgumentException("Expected number, got " + value);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof Long l) return BigDecimal.valueOf(l);
        if (value instanceof Double d) return BigDecimal.valueOf(d);
        if (value instanceof BigInteger bi) return new BigDecimal(bi);
        if (value instanceof String s) return new BigDecimal(s);
        throw new IllegalArgumentException("Expected decimal number, got " + value);
    }

    public static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Boolean.class || type == Character.class
                || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == BigInteger.class
                || type == BigDecimal.class || type == UUID.class || type == URI.class
                || type == Instant.class || type == LocalDate.class || type == LocalTime.class
                || type == LocalDateTime.class || type == OffsetDateTime.class || type == ZonedDateTime.class
                || type == Duration.class;
    }

    record RecordPlan(ComponentPlan[] components, MethodHandle constructor) {
        Object construct(Class<?> recordClass, Object[] args) {
            try {
                return (Object) constructor.invokeExact(args);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Throwable cause) {
                throw new IllegalArgumentException("Cannot construct " + recordClass.getSimpleName() + ": " + cause.getMessage(), cause);
            }
        }
    }

    record ComponentPlan(
            String wireName,
            Class<?> rawType,
            Type genericType,
            boolean isPrimitive,
            boolean isOptional,
            boolean explicitRequired,
            MethodHandle getter) {
        Object read(Object record) {
            try {
                return (Object) getter.invokeExact(record);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable cause) {
                throw new IllegalArgumentException("Cannot read record component " + wireName, cause);
            }
        }
    }
}
