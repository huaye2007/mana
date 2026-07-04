package cn.managame.config.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigChangeEventTest {
    @Test
    void shouldCopyInputsAndExposeImmutableCollections() {
        Map<String, String> oldConfig = new HashMap<>(Map.of("mode", "old"));
        Map<String, String> newConfig = new HashMap<>(Map.of("mode", "new"));
        Set<String> changedKeys = new HashSet<>(Set.of("mode"));
        Map<String, ChangeType> changeDetails = new HashMap<>(Map.of("mode", ChangeType.UPDATED));

        ConfigChangeEvent event = new ConfigChangeEvent(oldConfig, newConfig, changedKeys, changeDetails);

        oldConfig.put("late", "old");
        newConfig.put("late", "new");
        changedKeys.add("late");
        changeDetails.put("late", ChangeType.ADDED);

        assertEquals(Map.of("mode", "old"), event.getOldConfig());
        assertEquals(Map.of("mode", "new"), event.getNewConfig());
        assertEquals(Set.of("mode"), event.getChangedKeys());
        assertEquals(ChangeType.UPDATED, event.getChangeType("mode"));
        assertEquals(1, event.getChangeDetails().size());

        assertThrows(UnsupportedOperationException.class, () -> event.getOldConfig().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> event.getChangedKeys().add("x"));
    }

    @Test
    void shouldTreatNullInputsAsEmptyCollections() {
        ConfigChangeEvent event = new ConfigChangeEvent(null, null, null, null);

        assertTrue(event.getOldConfig().isEmpty());
        assertTrue(event.getNewConfig().isEmpty());
        assertTrue(event.getChangedKeys().isEmpty());
        assertTrue(event.getChangeDetails().isEmpty());
    }
}
