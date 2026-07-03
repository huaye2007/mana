package com.github.huaye2007.mana.config.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigValidatorsTest {
    @Test
    void requiredKeysShouldRejectMissingOrBlankValues() {
        ConfigValidator validator = ConfigValidators.requiredKeys("server.port", "server.host");

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(Map.of("server.port", "8080")));
        assertEquals("missing required config key: server.host", missing.getMessage());

        IllegalArgumentException blank = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(Map.of("server.port", "8080", "server.host", " ")));
        assertEquals("missing required config key: server.host", blank.getMessage());
    }

    @Test
    void rangeValidatorsShouldAcceptMissingValuesAndRejectInvalidValues() {
        ConfigValidator validator = ConfigValidators.allOf(
                ConfigValidators.intRange("server.port", 1, 65535),
                ConfigValidators.longRange("rpc.timeoutMillis", 1L, 30_000L),
                ConfigValidators.doubleRange("feature.ratio", 0.0D, 1.0D));

        assertDoesNotThrow(() -> validator.validate(Map.of()));
        assertDoesNotThrow(() -> validator.validate(Map.of(
                "server.port", "8080",
                "rpc.timeoutMillis", "3000",
                "feature.ratio", "0.25")));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("server.port", "70000")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("rpc.timeoutMillis", "slow")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("feature.ratio", "NaN")));
    }

    @Test
    void booleanValidatorShouldAcceptCanonicalValues() {
        ConfigValidator validator = ConfigValidators.booleanValue("feature.enabled");

        assertDoesNotThrow(() -> validator.validate(Map.of("feature.enabled", "true")));
        assertDoesNotThrow(() -> validator.validate(Map.of("feature.enabled", "FALSE")));
        assertDoesNotThrow(() -> validator.validate(Map.of("feature.enabled", "1")));
        assertDoesNotThrow(() -> validator.validate(Map.of("feature.enabled", "0")));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(Map.of("feature.enabled", "yes")));
        assertEquals("config key 'feature.enabled' must be a boolean: yes", error.getMessage());
    }

    @Test
    void matchesShouldValidatePattern() {
        ConfigValidator validator = ConfigValidators.matches(
                "server.host",
                Pattern.compile("[a-z0-9.-]+"));

        assertDoesNotThrow(() -> validator.validate(Map.of("server.host", "game-1.internal")));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("server.host", "bad host")));
    }

    @Test
    void factoryMethodsShouldRejectInvalidDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> ConfigValidators.intRange(" ", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> ConfigValidators.longRange("x", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> ConfigValidators.doubleRange("x", Double.NaN, 1));
        assertThrows(NullPointerException.class, () -> ConfigValidators.matches("x", null));
    }
}
