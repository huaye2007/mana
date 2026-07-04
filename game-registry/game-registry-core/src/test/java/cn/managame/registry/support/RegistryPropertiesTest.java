package cn.managame.registry.support;

import cn.managame.registry.exception.RegistryOperationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryPropertiesTest {
    @Test
    void getTrimsAndTreatsBlankAsMissing() {
        Properties properties = new Properties();
        properties.setProperty("name", "  game  ");
        properties.setProperty("blank", "  ");

        assertEquals("game", RegistryProperties.get(properties, "name"));
        assertNull(RegistryProperties.get(properties, "blank"));
        assertNull(RegistryProperties.get(null, "name"));
    }

    @Test
    void firstNonBlankReturnsFirstTrimmedValueOrFallback() {
        assertEquals("game", RegistryProperties.firstNonBlank("fallback", null, " ", " game "));
        assertEquals("fallback", RegistryProperties.firstNonBlank("fallback", null, " "));
    }

    @Test
    void parsesPositiveNumbersAndRejectsInvalidValues() {
        Properties properties = new Properties();
        properties.setProperty("int", " 12 ");
        properties.setProperty("long", "34");
        properties.setProperty("zero", "0");
        properties.setProperty("bad", "abc");
        properties.setProperty("overflow", String.valueOf((long) Integer.MAX_VALUE + 1));

        assertEquals(12, RegistryProperties.positiveInt(properties, "int", 1));
        assertEquals(34L, RegistryProperties.positiveLong(properties, "long", 1));
        assertEquals(9, RegistryProperties.positiveInt(properties, "missing", 9));
        assertThrows(RegistryOperationException.class,
                () -> RegistryProperties.positiveLong(properties, "zero", 1));
        assertThrows(RegistryOperationException.class,
                () -> RegistryProperties.positiveLong(properties, "bad", 1));
        assertThrows(RegistryOperationException.class,
                () -> RegistryProperties.positiveInt(properties, "overflow", 1));
    }

    @Test
    void applyHelpersOnlyInvokeWhenPresent() {
        Properties properties = new Properties();
        properties.setProperty("text", " value ");
        properties.setProperty("duration", "100");
        properties.setProperty("long", "200");
        properties.setProperty("int", "3");
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<Duration> duration = new AtomicReference<>();
        AtomicLong longValue = new AtomicLong();
        AtomicInteger intValue = new AtomicInteger();

        RegistryProperties.applyString(properties, "text", text::set);
        RegistryProperties.applyDurationMillis(properties, "duration", duration::set);
        RegistryProperties.applyPositiveLong(properties, "long", longValue::set);
        RegistryProperties.applyPositiveInt(properties, "int", intValue::set);
        RegistryProperties.applyString(properties, "missing", value -> {
            throw new AssertionError("missing key should not invoke consumer");
        });

        assertEquals("value", text.get());
        assertEquals(Duration.ofMillis(100), duration.get());
        assertEquals(200L, longValue.get());
        assertEquals(3, intValue.get());
    }

    @Test
    void parsesBooleanWithDefault() {
        assertTrue(RegistryProperties.booleanValue(" true ", false));
        assertTrue(RegistryProperties.booleanValue("TRUE", false));
        assertFalse(RegistryProperties.booleanValue("false", true));
        assertTrue(RegistryProperties.booleanValue(null, true));
        assertFalse(RegistryProperties.booleanValue(" ", false));
        assertThrows(RegistryOperationException.class,
                () -> RegistryProperties.booleanValue("yes", false));
    }
}
