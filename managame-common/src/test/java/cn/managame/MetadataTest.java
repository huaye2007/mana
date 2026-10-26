package cn.managame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetadataTest {
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);
    private static final MetadataKey<Long> SERVER = MetadataKey.longKey(2);
    private static final MetadataKey<Integer> LEVEL = MetadataKey.intKey(3);

    @Test void valuesAreReadByTheirKeys() {
        Metadata metadata = Metadata.builder().put(TRACE, "t1").put(SERVER, 7L).build();
        assertEquals("t1", metadata.get(TRACE));
        assertEquals(7L, metadata.get(SERVER));
        assertNull(metadata.get(LEVEL));
        assertEquals(5, metadata.get(LEVEL, 5));
        assertEquals(metadata.get(TRACE), Metadata.wrap(metadata.bytes()).get(TRACE), "the bytes are the wire form");
    }

    @Test void toBuilderDerivesWithoutChangingTheOriginal() {
        Metadata original = Metadata.builder().put(TRACE, "t1").put(SERVER, 7L).build();
        Metadata derived = original.toBuilder().put(SERVER, 8L).put(LEVEL, 3).build();
        assertEquals("t1", derived.get(TRACE));
        assertEquals(8L, derived.get(SERVER), "a put replaces the value");
        assertEquals(3, derived.get(LEVEL));
        assertEquals(7L, original.get(SERVER));
        assertNull(original.get(LEVEL));
        assertTrue(Metadata.empty().toBuilder().build().isEmpty());
    }

    @Test void malformedBytesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 1, 0}), "truncated header");
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 0, 0, 0}), "key zero");
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 1, 0, 5, 1}), "truncated value");
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 1, 0, 0, 0, 1, 0, 0}), "duplicate key");
    }
}
