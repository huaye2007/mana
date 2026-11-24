package cn.managame.ai.internal;

import cn.managame.ai.AiCodec;
import cn.managame.ai.annotation.McpParam;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
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
 * records with no third-party dependency. Public only to be reachable across {@code managame-ai}'s packages; not API.
 */
public final class BasicAiCodec implements AiCodec {
    public static final BasicAiCodec INSTANCE = new BasicAiCodec();

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
                return args.length == 2 && args[0] == String.class && supports(args[1]);
        }
        return false;
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
                if (pt.getActualTypeArguments()[0] != String.class)
                    throw new IllegalArgumentException("Only Map<String, ?> is supported by the basic codec");
                return convertMap(value, pt.getActualTypeArguments()[1]);
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
        if (raw == int.class || raw == Integer.class) return toExactLong(value, raw).intValueExact();
        if (raw == long.class || raw == Long.class) return toExactLong(value, raw).longValueExact();
        if (raw == short.class || raw == Short.class) return toExactLong(value, raw).shortValueExact();
        if (raw == byte.class || raw == Byte.class) return toExactLong(value, raw).byteValueExact();
        if (raw == double.class || raw == Double.class) return toDouble(value);
        if (raw == float.class || raw == Float.class) return (float) toDouble(value);
        if (raw == BigInteger.class) return toExactLong(value, raw);
        if (raw == BigDecimal.class) return toBigDecimal(value);
        if (raw == char.class || raw == Character.class) {
            if (value instanceof String s && s.length() == 1) return s.charAt(0);
            throw new IllegalArgumentException("Expected single-character string, got " + value);
        }
        if (raw == UUID.class) {
            if (value instanceof String s) return UUID.fromString(s);
            throw new IllegalArgumentException("Expected UUID string, got " + value);
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
        if (Map.class.isAssignableFrom(raw)) return convertMap(value, Object.class);
        if (raw.isRecord()) return convertRecord(value, raw);
        throw new IllegalArgumentException("No basic decoding for " + raw.getName() + "; configure an AiCodec");
    }

    private static List<Object> convertList(Object value, Type elementType) {
        if (!(value instanceof List<?> rawList)) throw new IllegalArgumentException("Expected JSON array");
        List<Object> out = new ArrayList<>(rawList.size());
        for (Object item : rawList) out.add(convert(item, elementType));
        return out;
    }

    private static Map<String, Object> convertMap(Object value, Type valueType) {
        if (!(value instanceof Map<?, ?> rawMap)) throw new IllegalArgumentException("Expected JSON object");
        Map<String, Object> out = new LinkedHashMap<>(rawMap.size());
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            out.put(entry.getKey().toString(), convert(entry.getValue(), valueType));
        }
        return out;
    }

    private static Object convertRecord(Object value, Class<?> recordClass) {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected JSON object for " + recordClass.getSimpleName());
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            String fieldName = componentFieldName(component);
            boolean present = map.containsKey(fieldName);
            Object raw = map.get(fieldName);
            if (!present || raw == null) {
                if (component.getType() == Optional.class) {
                    args[i] = Optional.empty();
                } else if (component.getType().isPrimitive()) {
                    throw new IllegalArgumentException("Missing required field '" + fieldName + "'");
                } else {
                    McpParam annotation = component.getAnnotation(McpParam.class);
                    if (annotation != null && annotation.required() && !present) {
                        throw new IllegalArgumentException("Missing required field '" + fieldName + "'");
                    }
                    args[i] = null;
                }
            } else {
                args[i] = convert(raw, component.getGenericType());
            }
        }
        try {
            Constructor<?> ctor = recordClass.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalArgumentException("Cannot construct " + recordClass.getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }

    static String componentFieldName(RecordComponent component) {
        McpParam param = component.getAnnotation(McpParam.class);
        return param != null && !param.name().isEmpty() ? param.name() : component.getName();
    }

    private static BigInteger toExactLong(Object value, Class<?> target) {
        if (value instanceof Long l) return BigInteger.valueOf(l);
        if (value instanceof Integer i) return BigInteger.valueOf(i);
        if (value instanceof BigInteger bi) return bi;
        if (value instanceof Double d && Math.rint(d) == d && Double.isFinite(d)) {
            return BigDecimal.valueOf(d).toBigIntegerExact();
        }
        throw new IllegalArgumentException("Expected integer for " + target.getSimpleName() + ", got " + value);
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
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
                || type == BigDecimal.class || type == UUID.class;
    }
}
