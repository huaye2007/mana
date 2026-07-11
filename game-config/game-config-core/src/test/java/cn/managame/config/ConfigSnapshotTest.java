package cn.managame.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSnapshotTest {
    @Test void providesTypedImmutableValues() {
        ConfigSnapshot snapshot = new ConfigSnapshot(1, Instant.now(), Map.of(
                "port", "8080", "enabled", "yes", "timeout", "PT5S"));
        assertEquals(8080, snapshot.getInt("port", 0));
        assertTrue(snapshot.getBoolean("enabled", false));
        assertEquals(5, snapshot.getDuration("timeout", null).toSeconds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.values().put("x", "y"));
        assertThrows(java.util.NoSuchElementException.class, () -> snapshot.require("missing"));
    }
}
