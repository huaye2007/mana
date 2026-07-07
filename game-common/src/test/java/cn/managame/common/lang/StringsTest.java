package cn.managame.common.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringsTest {

    @Test
    void isBlankCoversNullAndWhitespace() {
        assertTrue(Strings.isBlank(null));
        assertTrue(Strings.isBlank("   "));
        assertFalse(Strings.isBlank(" x "));
        assertTrue(Strings.isNotBlank(" x "));
    }

    @Test
    void normalizeToLowerTrimsAndLowercases() {
        assertNull(Strings.normalizeToLower(null));
        assertNull(Strings.normalizeToLower("   "));
        assertEquals("nacos", Strings.normalizeToLower("  Nacos "));
    }

    @Test
    void firstNonBlankReturnsFirstMeaningfulValue() {
        assertEquals("b", Strings.firstNonBlank(null, "  ", "b", "c"));
        assertNull(Strings.firstNonBlank(null, "  "));
        assertNull(Strings.firstNonBlank((String[]) null));
    }

    @Test
    void requireNonBlankReturnsValueOrThrows() {
        assertEquals("x", Strings.requireNonBlank("x", "field"));
        assertThrows(IllegalArgumentException.class, () -> Strings.requireNonBlank("  ", "field"));
    }
}
