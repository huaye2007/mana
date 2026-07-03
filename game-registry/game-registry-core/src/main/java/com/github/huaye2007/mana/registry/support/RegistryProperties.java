package com.github.huaye2007.mana.registry.support;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

public final class RegistryProperties {
    private RegistryProperties() {
    }

    /**
     * Copies {@code source} into a fresh mutable map, dropping entries whose key or value is null.
     * Used by providers when round-tripping instance metadata through a registry SDK.
     */
    public static Map<String, String> copyStringMap(Map<String, String> source) {
        Map<String, String> copy = new HashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
        }
        return copy;
    }

    /**
     * Validates that {@code key} is a non-blank String and {@code value} a non-null String, then
     * stores the pair in {@code target}. Shared by the config/property builders that only accept
     * String-keyed, String-valued properties.
     */
    public static void putString(Properties target, Object key, Object value) {
        if (key == null) {
            throw new RegistryOperationException("property key must not be blank");
        }
        if (!(key instanceof String propertyKey)) {
            throw new RegistryOperationException("property key must be a string");
        }
        if (propertyKey.isBlank()) {
            throw new RegistryOperationException("property key must not be blank");
        }
        if (value == null) {
            throw new RegistryOperationException("property value must not be null");
        }
        if (!(value instanceof String propertyValue)) {
            throw new RegistryOperationException("property value must be a string");
        }
        target.setProperty(propertyKey, propertyValue);
    }

    public static String get(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return isBlank(value) ? null : value.trim();
    }

    public static String firstNonBlank(String fallback, String... values) {
        if (values != null) {
            for (String value : values) {
                if (!isBlank(value)) {
                    return value.trim();
                }
            }
        }
        return fallback;
    }

    public static int positiveInt(Properties properties, String key, int defaultValue) {
        String value = get(properties, key);
        if (value == null) {
            return defaultValue;
        }
        long parsed = positiveLong(key, value);
        if (parsed > Integer.MAX_VALUE) {
            throw new RegistryOperationException(key + " must be <= " + Integer.MAX_VALUE);
        }
        return (int) parsed;
    }

    public static long positiveLong(Properties properties, String key, long defaultValue) {
        String value = get(properties, key);
        return value == null ? defaultValue : positiveLong(key, value);
    }

    public static long positiveLong(String key, String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new RegistryOperationException(key + " must be > 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new RegistryOperationException(key + " must be a number", e);
        }
    }

    public static boolean booleanValue(String value, boolean defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        throw new RegistryOperationException("boolean value must be true or false");
    }

    public static void applyString(Properties properties, String key, Consumer<String> consumer) {
        String value = get(properties, key);
        if (value != null) {
            consumer.accept(value);
        }
    }

    public static void applyDurationMillis(Properties properties, String key, Consumer<Duration> consumer) {
        String value = get(properties, key);
        if (value != null) {
            consumer.accept(Duration.ofMillis(positiveLong(key, value)));
        }
    }

    public static void applyPositiveLong(Properties properties, String key, LongConsumer consumer) {
        String value = get(properties, key);
        if (value != null) {
            consumer.accept(positiveLong(key, value));
        }
    }

    public static void applyPositiveInt(Properties properties, String key, IntConsumer consumer) {
        String value = get(properties, key);
        if (value != null) {
            consumer.accept(positiveInt(properties, key, 1));
        }
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
