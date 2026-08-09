package cn.managame.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSnapshotTest {
    private enum Mode { MEMORY, NACOS }

    @Test void providesTypedImmutableValues() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1, Instant.now(), Map.of(
                "port", "8080", "enabled", "yes", "timeout", "PT5S", "ratio", "0.75", "mode", "nacos"));
        assertEquals(8080, snapshot.getInt("port", 0));
        assertTrue(snapshot.getBoolean("enabled", false));
        assertEquals(5, snapshot.getDuration("timeout", null).toSeconds());
        assertEquals(0.75, snapshot.getDouble("ratio", 0), 1e-9);
        assertEquals(0.75f, snapshot.getFloat("ratio", 0), 1e-6f);
        assertEquals(Mode.NACOS, snapshot.getEnum("mode", Mode.class, Mode.MEMORY));
        assertEquals(Mode.MEMORY, snapshot.getEnum("absent", Mode.class, Mode.MEMORY));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values().put("x", "y"));
    }

    @Test void reportsMissingAndMalformedValuesAsConfigException() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1, Instant.now(), Map.of(
                "port", "not-a-number", "enabled", "maybe", "timeout", "5s"));
        assertThrows(ConfigException.class, () -> snapshot.require("missing"));
        assertThrows(ConfigException.class, () -> snapshot.getInt("port", 0));
        assertThrows(ConfigException.class, () -> snapshot.getLong("port", 0));
        assertThrows(ConfigException.class, () -> snapshot.getDouble("port", 0));
        assertThrows(ConfigException.class, () -> snapshot.getBoolean("enabled", false));
        assertThrows(ConfigException.class, () -> snapshot.getDuration("timeout", null));
        assertThrows(ConfigException.class, () -> snapshot.getEnum("enabled", Mode.class, Mode.MEMORY));
        // The key is named in the message, so a bad value is diagnosable without a stack trace.
        assertTrue(assertThrows(ConfigException.class, () -> snapshot.getInt("port", 0))
                .getMessage().contains("port"));
    }

    @Test void readsListsFromIndexedKeysAndFromCommaSeparatedValues() {
        ConfigSnapshot indexed = new ConfigSnapshot(1, Instant.now(), Map.of(
                "regions", "[\"cn\",\"us\"]", "regions[0]", "cn", "regions[1]", "us"));
        // Indexed keys win, so a JSON array survives the flattening round trip as a list.
        assertEquals(List.of("cn", "us"), indexed.getList("regions"));

        ConfigSnapshot plain = new ConfigSnapshot(1, Instant.now(), Map.of(
                "regions", "cn, us ,, jp", "ports", "1,2,3"));
        assertEquals(List.of("cn", "us", "jp"), plain.getList("regions"));
        assertEquals(List.of(1, 2, 3), plain.getIntList("ports"));
        assertEquals(List.of(), plain.getList("absent"));
        assertThrows(ConfigException.class, () -> plain.getIntList("regions"));
    }

    @Test void scopesKeysBySubMap() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1, Instant.now(), Map.of(
                "game.db.url", "jdbc:x", "game.db.user", "root", "game.server.port", "8080", "game.db", "ignored"));
        assertEquals(Map.of("url", "jdbc:x", "user", "root"), snapshot.subMap("game.db"));
        assertEquals(Map.of("url", "jdbc:x", "user", "root"), snapshot.subMap("game.db."));
    }
}
