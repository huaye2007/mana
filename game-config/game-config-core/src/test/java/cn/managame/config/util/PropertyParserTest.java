package cn.managame.config.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyParserTest {

    @Test
    void shouldParseSimpleKeyValue() {
        Map<String, String> result = PropertyParser.parse("key=value");
        assertEquals("value", result.get("key"));
    }

    @Test
    void shouldParseMultipleLines() {
        Map<String, String> result = PropertyParser.parse("host=localhost\nport=8080\ndebug=true");
        assertEquals("localhost", result.get("host"));
        assertEquals("8080", result.get("port"));
        assertEquals("true", result.get("debug"));
    }

    @Test
    void shouldSkipCommentsAndBlanks() {
        Map<String, String> result = PropertyParser.parse("# comment\nkey=value\n\n# another comment\nfoo=bar");
        assertEquals("value", result.get("key"));
        assertEquals("bar", result.get("foo"));
        assertEquals(2, result.size());
    }

    @Test
    void shouldTrimKeysAndValues() {
        Map<String, String> result = PropertyParser.parse("  key  =  value  ");
        assertEquals("value", result.get("key"));
    }

    @Test
    void shouldHandleNullContent() {
        Map<String, String> result = PropertyParser.parse(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleEmptyContent() {
        Map<String, String> result = PropertyParser.parse("");
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldHandleValueWithEquals() {
        Map<String, String> result = PropertyParser.parse("url=jdbc:mysql://host:3306/db?useSSL=false");
        assertEquals("jdbc:mysql://host:3306/db?useSSL=false", result.get("url"));
    }

    @Test
    void shouldSupportStandardPropertiesSyntax() {
        Map<String, String> result = PropertyParser.parse("""
                ! comment
                path: /game/config
                escaped\\ key=value\\ with\\ spaces
                multi=line1\\
                  line2
                """);

        assertEquals("/game/config", result.get("path"));
        assertEquals("value with spaces", result.get("escaped key"));
        assertEquals("line1line2", result.get("multi"));
    }
}
