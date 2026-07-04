package cn.managame.config.api;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Common validators for production config keys.
 */
public final class ConfigValidators {
    private ConfigValidators() {
    }

    public static ConfigValidator allOf(ConfigValidator... validators) {
        return config -> {
            if (validators == null) {
                return;
            }
            for (ConfigValidator validator : validators) {
                if (validator != null) {
                    validator.validate(config);
                }
            }
        };
    }

    public static ConfigValidator requiredKeys(String... keys) {
        return config -> {
            if (keys == null) {
                return;
            }
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                String value = value(config, key);
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("missing required config key: " + key);
                }
            }
        };
    }

    public static ConfigValidator intRange(String key, int minInclusive, int maxInclusive) {
        requireRange(key, minInclusive, maxInclusive);
        return config -> {
            String raw = value(config, key);
            if (raw == null || raw.isBlank()) {
                return;
            }
            int parsed;
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("config key '" + key + "' must be an integer: " + raw, e);
            }
            if (parsed < minInclusive || parsed > maxInclusive) {
                throw new IllegalArgumentException(
                        "config key '" + key + "' must be between "
                                + minInclusive + " and " + maxInclusive + ": " + raw);
            }
        };
    }

    public static ConfigValidator longRange(String key, long minInclusive, long maxInclusive) {
        requireRange(key, minInclusive, maxInclusive);
        return config -> {
            String raw = value(config, key);
            if (raw == null || raw.isBlank()) {
                return;
            }
            long parsed;
            try {
                parsed = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("config key '" + key + "' must be a long: " + raw, e);
            }
            if (parsed < minInclusive || parsed > maxInclusive) {
                throw new IllegalArgumentException(
                        "config key '" + key + "' must be between "
                                + minInclusive + " and " + maxInclusive + ": " + raw);
            }
        };
    }

    public static ConfigValidator doubleRange(String key, double minInclusive, double maxInclusive) {
        requireKey(key);
        if (Double.isNaN(minInclusive) || Double.isNaN(maxInclusive)
                || minInclusive > maxInclusive) {
            throw new IllegalArgumentException("invalid range for config key: " + key);
        }
        return config -> {
            String raw = value(config, key);
            if (raw == null || raw.isBlank()) {
                return;
            }
            double parsed;
            try {
                parsed = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("config key '" + key + "' must be a double: " + raw, e);
            }
            if (Double.isNaN(parsed) || parsed < minInclusive || parsed > maxInclusive) {
                throw new IllegalArgumentException(
                        "config key '" + key + "' must be between "
                                + minInclusive + " and " + maxInclusive + ": " + raw);
            }
        };
    }

    public static ConfigValidator booleanValue(String key) {
        requireKey(key);
        return config -> {
            String raw = value(config, key);
            if (raw == null || raw.isBlank()) {
                return;
            }
            String trimmed = raw.trim();
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)
                    || "1".equals(trimmed) || "0".equals(trimmed)) {
                return;
            }
            throw new IllegalArgumentException("config key '" + key + "' must be a boolean: " + raw);
        };
    }

    public static ConfigValidator matches(String key, Pattern pattern) {
        requireKey(key);
        Objects.requireNonNull(pattern, "pattern must not be null");
        return config -> {
            String raw = value(config, key);
            if (raw == null || raw.isBlank()) {
                return;
            }
            if (!pattern.matcher(raw.trim()).matches()) {
                throw new IllegalArgumentException("config key '" + key + "' does not match required pattern: " + raw);
            }
        };
    }

    private static String value(Map<String, String> config, String key) {
        return config == null ? null : config.get(key);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key must not be blank");
        }
    }

    private static void requireRange(String key, long minInclusive, long maxInclusive) {
        requireKey(key);
        if (minInclusive > maxInclusive) {
            throw new IllegalArgumentException("invalid range for config key: " + key);
        }
    }
}
