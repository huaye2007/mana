package com.github.huaye2007.mana.jpa.rdb.metadata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies Java-side defaults declared by {@code @Column(defaultValue = "...")}.
 */
public final class RdbDefaultValues {

    private static final ObjectMapper JSON = configureObjectMapper(new ObjectMapper());

    private RdbDefaultValues() {
    }

    public static void applyInsertDefaults(RdbEntityMetadata metadata, Object entity) {
        for (RdbFieldMetadata field : metadata.fields()) {
            if (field.defaultValue().isEmpty()) {
                continue;
            }
            Object current = field.accessor().get(entity);
            if (!shouldApplyDefault(field, current)) {
                continue;
            }
            parseDefaultValue(field).ifPresent(value -> field.accessor().set(entity, value));
        }
    }

    private static boolean shouldApplyDefault(RdbFieldMetadata field, Object value) {
        if (value == null) {
            return true;
        }
        Class<?> type = field.javaType();
        return type.isPrimitive() && value.equals(primitiveZeroValue(type));
    }

    private static Object primitiveZeroValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Optional<Object> parseDefaultValue(RdbFieldMetadata field) {
        String value = field.defaultValue();
        Class<?> type = field.javaType();
        try {
            if (field.isJsonField()) {
                JavaType javaType = JSON.getTypeFactory().constructType(field.javaField().getGenericType());
                return Optional.ofNullable(JSON.readValue(value, javaType));
            }
            if (type == String.class) return Optional.of(value);
            if (type == int.class || type == Integer.class) return Optional.of(Integer.parseInt(value.trim()));
            if (type == long.class || type == Long.class) return Optional.of(Long.parseLong(value.trim()));
            if (type == short.class || type == Short.class) return Optional.of(Short.parseShort(value.trim()));
            if (type == byte.class || type == Byte.class) return Optional.of(Byte.parseByte(value.trim()));
            if (type == float.class || type == Float.class) return Optional.of(Float.parseFloat(value.trim()));
            if (type == double.class || type == Double.class) return Optional.of(Double.parseDouble(value.trim()));
            if (type == boolean.class || type == Boolean.class) return Optional.of(parseBooleanDefault(field, value));
            if (type == char.class || type == Character.class) {
                return value.isEmpty() ? Optional.empty() : Optional.of(value.charAt(0));
            }
            if (type == BigDecimal.class) return Optional.of(new BigDecimal(value.trim()));
            if (type == BigInteger.class) return Optional.of(new BigInteger(value.trim()));
            if (type == UUID.class) return Optional.of(UUID.fromString(value.trim()));
            if (type == Instant.class) return Optional.of(Instant.parse(value.trim()));
            if (type == LocalDateTime.class) return Optional.of(LocalDateTime.parse(value.trim()));
            if (type == LocalDate.class) return Optional.of(LocalDate.parse(value.trim()));
            if (type == LocalTime.class) return Optional.of(LocalTime.parse(value.trim()));
            if (type.isEnum()) {
                return Optional.of(Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), value.trim()));
            }
            return Optional.empty();
        } catch (RuntimeException | JsonProcessingException e) {
            throw new GameJpaException("Invalid default value for field "
                    + field.propertyName() + ": " + value, e);
        }
    }

    private static ObjectMapper configureObjectMapper(ObjectMapper mapper) {
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private static boolean parseBooleanDefault(RdbFieldMetadata field, String value) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new GameJpaException("Invalid boolean default value for field "
                    + field.propertyName() + ": " + value);
        };
    }
}
