package cn.managame.ai;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class ToolSchema {
    static String describe(ObjectMapper mapper, Class<?> type, boolean stringLongs) {
        ObjectNode schema = node(mapper, mapper.constructType(type), new HashSet<>(), stringLongs);
        if (!"object".equals(schema.path("type").asText())) throw new IllegalArgumentException("Tool arguments must be an object: " + type);
        return schema.toString();
    }

    /** Describes any supported response or output type, or returns null when unstructured or unsupported. */
    static String describeAny(ObjectMapper mapper, Class<?> type, boolean stringLongs) {
        if (type == null || type == void.class || type == Void.class || type == Object.class) return null;
        try {
            return node(mapper, mapper.constructType(type), new HashSet<>(), stringLongs).toString();
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static ObjectNode node(ObjectMapper mapper, JavaType type, Set<JavaType> stack, boolean stringLongs) {
        if (!stack.add(type)) throw new IllegalArgumentException("Recursive tool input needs a custom schema: " + type);
        try {
            ObjectNode schema = mapper.createObjectNode();
            Class<?> raw = type.getRawClass();
            if (raw == String.class) return schema.put("type", "string");
            if (raw == UUID.class) return schema.put("type", "string").put("format", "uuid");
            if (raw == Instant.class || raw == LocalDateTime.class) return schema.put("type", "string").put("format", "date-time");
            if (raw == LocalDate.class) return schema.put("type", "string").put("format", "date");
            if (raw == Duration.class) return schema.put("type", "string").put("format", "duration");
            if (raw == boolean.class || raw == Boolean.class) return schema.put("type", "boolean");
            if (raw.isEnum()) {
                schema.put("type", "string");
                var values = schema.putArray("enum");
                for (Object value : raw.getEnumConstants()) values.add(mapper.valueToTree(value));
                return schema;
            }
            if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class
                    || raw == BigDecimal.class) return schema.put("type", "number");
            if (raw == long.class || raw == Long.class) {
                return stringLongs
                        ? schema.put("type", "string").put("format", "int64").put("pattern", "^-?[0-9]+$")
                        : schema.put("type", "integer").put("format", "int64");
            }
            if (raw == byte.class || raw == short.class || raw == int.class
                    || raw == Byte.class || raw == Short.class || raw == Integer.class
                    || raw == BigInteger.class) return schema.put("type", "integer");
            if (type.isMapLikeType()) {
                if (!type.getKeyType().hasRawClass(String.class)) throw new IllegalArgumentException("Use string map keys: " + type);
                return schema.put("type", "object").set("additionalProperties", node(mapper, type.getContentType(), stack, stringLongs));
            }
            if (raw == byte[].class) return schema.put("type", "string").put("contentEncoding", "base64");
            if (type.isArrayType() || type.isCollectionLikeType())
                return schema.put("type", "array").set("items", node(mapper, type.getContentType(), stack, stringLongs));
            if (raw == Object.class || raw.isPrimitive() || raw.getPackageName().startsWith("java."))
                throw new IllegalArgumentException("Unsupported tool property: " + type);
            schema.put("type", "object").put("additionalProperties", false);
            var properties = schema.putObject("properties");
            var required = mapper.createArrayNode();
            boolean record = raw.isRecord();
            for (BeanPropertyDefinition property : mapper.getDeserializationConfig().introspect(type).findProperties()) {
                if (!property.couldDeserialize()) continue;
                ObjectNode propSchema = node(mapper, property.getPrimaryType(), stack, stringLongs);
                AiParam param = findParam(property);
                if (param != null && !param.value().isBlank()) propSchema.put("description", param.value());
                properties.set(property.getName(), propSchema);
                if (isRequired(property, param, record)) required.add(property.getName());
            }
            if (!required.isEmpty()) schema.set("required", required);
            return schema;
        } finally { stack.remove(type); }
    }

    private static AiParam findParam(BeanPropertyDefinition property) {
        AiParam found = annotation(property.getConstructorParameter());
        if (found != null) return found;
        found = annotation(property.getField());
        if (found != null) return found;
        found = annotation(property.getGetter());
        if (found != null) return found;
        return annotation(property.getPrimaryMember());
    }

    private static AiParam annotation(AnnotatedMember member) {
        return member == null ? null : member.getAnnotation(AiParam.class);
    }

    private static boolean isRequired(BeanPropertyDefinition property, AiParam param, boolean record) {
        if (property.getPrimaryType().isPrimitive()) return true;
        if (param != null) return param.required();
        return record || property.isRequired();
    }

    private ToolSchema() {}
}
