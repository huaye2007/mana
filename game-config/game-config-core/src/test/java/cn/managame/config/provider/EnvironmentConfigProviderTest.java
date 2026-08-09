package cn.managame.config.provider;

import cn.managame.config.ConfigLayer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentConfigProviderTest {

    private static Map<String, String> environment() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("GAME_DB_URL", "jdbc:mysql://localhost:3306/game");
        values.put("GAME_SERVER_PORT", "9090");
        values.put("GAME_FEATURE__FLAG", "on");
        values.put("PATH", "/usr/bin");
        return values;
    }

    @Test
    void mapsVariableNamesToDottedKeys() {
        var source = new EnvironmentConfigProvider.EnvironmentSource(
                ConfigLayer.environment("GAME_"), EnvironmentConfigProviderTest::environment);
        Map<String, String> values = source.load();
        assertEquals("jdbc:mysql://localhost:3306/game", values.get("game.db.url"));
        assertEquals("9090", values.get("game.server.port"));
        // A doubled underscore is a literal one, so a key can contain an underscore of its own.
        assertEquals("on", values.get("game.feature_flag"));
        // The prefix filters; it does not become part of the key namespace unless stripped.
        assertFalse(values.containsKey("path"));
    }

    @Test
    void readsEverythingWhenPrefixIsEmpty() {
        var source = new EnvironmentConfigProvider.EnvironmentSource(
                ConfigLayer.environment(""), EnvironmentConfigProviderTest::environment);
        assertTrue(source.load().containsKey("path"));
    }

    @Test
    void stripsThePrefixWhenAsked() {
        var source = new EnvironmentConfigProvider.EnvironmentSource(
                ConfigLayer.builder(ConfigLayer.ENVIRONMENT).property("prefix", "GAME_").property("strip", "true").build(),
                EnvironmentConfigProviderTest::environment);
        Map<String, String> values = source.load();
        assertEquals("9090", values.get("server.port"));
        assertFalse(values.containsKey("game.server.port"));
    }

    @Test
    void systemPropertiesKeepTheirNames() {
        Properties properties = new Properties();
        properties.setProperty("game.db.url", "jdbc:h2:mem:test");
        properties.setProperty("unrelated.key", "x");
        var source = new SystemPropertiesConfigProvider.SystemPropertiesSource(
                ConfigLayer.systemProperties("game."), () -> properties);
        Map<String, String> values = source.load();
        assertEquals("jdbc:h2:mem:test", values.get("game.db.url"));
        assertFalse(values.containsKey("unrelated.key"));
    }

    @Test
    void systemPropertiesCanStripTheirPrefix() {
        Properties properties = new Properties();
        properties.setProperty("game.db.url", "jdbc:h2:mem:test");
        var source = new SystemPropertiesConfigProvider.SystemPropertiesSource(
                ConfigLayer.builder(ConfigLayer.SYSTEM_PROPERTIES).property("prefix", "game.").property("strip", "true").build(),
                () -> properties);
        assertEquals("jdbc:h2:mem:test", source.load().get("db.url"));
    }
}
