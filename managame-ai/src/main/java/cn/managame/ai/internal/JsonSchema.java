package cn.managame.ai.internal;

import cn.managame.ai.annotation.McpParam;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds MCP-compatible JSON Schema strings once at tool registration time from Java parameters, records and POJOs.
 * Public only to be reachable across {@code managame-ai}'s packages; not API.
 */
public final class JsonSchema {
    public static final String EMPTY_OBJECT = "{\"type\":\"object\",\"properties\":{}}";

    private JsonSchema() {}

    /** Schema for an unwrapped single-DTO argument (record or POJO), whose members become top-level properties. */
    public static String forObjectType(Class<?> type) {
        Map<String, Object> schema = objectSchema(type, new IdentityHashMap<>());
        return Json.write(schema);
    }

    /** Schema for a method's explicit parameter list (excluding any leading McpToolContext). */
    public static String forParameters(List<ParamSpec> params) {
        if (params.isEmpty()) return EMPTY_OBJECT;
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        Map<Class<?>, Boolean> visiting = new IdentityHashMap<>();
        for (ParamSpec param : params) {
            Map<String, Object> prop = typeSchema(param.type(), visiting);
            if (!param.description().isEmpty()) prop.put("description", param.description());
            properties.put(param.name(), prop);
            if (param.required()) required.add(param.name());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        if (!required.isEmpty()) root.put("required", required);
        return Json.write(root);
    }

    public record ParamSpec(String name, String description, boolean required, Type type, Class<?> rawType) {
        public static ParamSpec of(Parameter parameter, String name, String description, boolean required) {
            return new ParamSpec(name, description, required, parameter.getParameterizedType(), parameter.getType());
        }
    }

    private static Map<String, Object> typeSchema(Type type, Map<Class<?>, Boolean> visiting) {
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            Type[] args = pt.getActualTypeArguments();
            if (raw == Optional.class && args.length == 1) {
                return typeSchema(args[0], visiting);
            }
            if (Collection.class.isAssignableFrom(raw) && args.length == 1) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "array");
                schema.put("items", typeSchema(args[0], visiting));
                return schema;
            }
            if (Map.class.isAssignableFrom(raw) && args.length == 2) {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("additionalProperties", typeSchema(args[1], visiting));
                return schema;
            }
            return typeSchema(raw, visiting);
        }
        if (!(type instanceof Class<?> raw)) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("type", "object");
            return fallback;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
            return schema;
        }
        if (raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == BigInteger.class) {
            schema.put("type", "integer");
            return schema;
        }
        if (raw == float.class || raw == Float.class || raw == double.class || raw == Double.class
                || raw == BigDecimal.class) {
            schema.put("type", "number");
            return schema;
        }
        if (raw == String.class || CharSequence.class.isAssignableFrom(raw)
                || raw == char.class || raw == Character.class || raw == UUID.class
                || raw == URI.class || raw == Duration.class || TemporalAccessor.class.isAssignableFrom(raw)) {
            schema.put("type", "string");
            return schema;
        }
        if (raw.isEnum()) {
            schema.put("type", "string");
            Object[] constants = raw.getEnumConstants();
            List<String> names = new ArrayList<>(constants.length);
            for (Object constant : constants) names.add(((Enum<?>) constant).name());
            schema.put("enum", names);
            return schema;
        }
        if (raw.isArray()) {
            schema.put("type", "array");
            schema.put("items", typeSchema(raw.getComponentType(), visiting));
            return schema;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            schema.put("type", "array");
            return schema;
        }
        if (Map.class.isAssignableFrom(raw) || raw == Object.class || raw.isInterface()) {
            schema.put("type", "object");
            return schema;
        }
        return objectSchema(raw, visiting);
    }

    private static Map<String, Object> objectSchema(Class<?> type, Map<Class<?>, Boolean> visiting) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        String pkg = type.getPackageName();
        if (pkg.startsWith("java.") || pkg.startsWith("javax.")) return root;
        if (visiting.put(type, Boolean.TRUE) != null) return root;
        try {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    McpParam param = component.getAnnotation(McpParam.class);
                    String propName = param != null && !param.name().isEmpty() ? param.name() : component.getName();
                    addProperty(properties, required, propName, component.getGenericType(), component.getType(), param, visiting);
                }
            } else {
                List<Field> fields = collectFields(type);
                for (Field field : fields) {
                    McpParam param = field.getAnnotation(McpParam.class);
                    String propName = param != null && !param.name().isEmpty() ? param.name() : field.getName();
                    addProperty(properties, required, propName, field.getGenericType(), field.getType(), param, visiting);
                }
            }
            root.put("properties", properties);
            if (!required.isEmpty()) root.put("required", required);
            return root;
        } finally {
            visiting.remove(type);
        }
    }

    private static void addProperty(Map<String, Object> properties, List<String> required,
            String name, Type genericType, Class<?> rawType, McpParam param, Map<Class<?>, Boolean> visiting) {
        Map<String, Object> prop = typeSchema(genericType, visiting);
        if (param != null && !param.value().isEmpty()) prop.put("description", param.value());
        properties.put(name, prop);
        boolean isRequired = rawType.isPrimitive()
                || (rawType != Optional.class && (param == null || param.required()));
        if (isRequired) required.add(name);
    }

    private static List<Field> collectFields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) hierarchy.add(c);
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = hierarchy.size() - 1; i >= 0; i--) {
            for (Field field : hierarchy.get(i).getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) continue;
                if (seen.add(field.getName())) fields.add(field);
            }
        }
        return fields;
    }
}
