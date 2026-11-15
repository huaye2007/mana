package cn.managame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test void withSetsOneValueWithoutChangingTheOriginal() {
        Metadata original = Metadata.builder().put(TRACE, "t1").put(SERVER, 7L).build();
        Metadata added = original.with(LEVEL, 3);
        assertEquals(3, added.get(LEVEL));
        assertEquals("t1", added.get(TRACE));
        assertEquals(7L, added.get(SERVER));
        assertNull(original.get(LEVEL), "the original is as it was");

        Metadata replaced = original.with(TRACE, "a longer trace").with(SERVER, 8L);
        assertEquals("a longer trace", replaced.get(TRACE));
        assertEquals(8L, replaced.get(SERVER));
        assertEquals("t1", original.get(TRACE));
        assertEquals(7L, original.get(SERVER));

        assertArrayEquals(original.toBuilder().put(TRACE, "t2").put(LEVEL, 3).build().bytes(), original.with(TRACE, "t2").with(LEVEL, 3).bytes(),
                "the bytes a builder makes of the same puts");
        assertEquals(3, Metadata.wrap(added.bytes()).get(LEVEL), "the bytes are the wire form");
        assertEquals("t1", Metadata.empty().with(TRACE, "t1").get(TRACE));
        assertTrue(Metadata.empty().isEmpty(), "the shared empty metadata stays empty");
    }

    @Test void withRejectsWhatDoesNotFit() {
        MetadataKey<byte[]> blob = MetadataKey.bytesKey(4);
        Metadata original = Metadata.builder().put(TRACE, "t1").build();
        assertThrows(IllegalArgumentException.class, () -> original.with(blob, new byte[65536]), "a value too large");
        assertThrows(IllegalArgumentException.class, () -> original.with(blob, new byte[65530]), "too large with the rest");
        assertEquals(65531, Metadata.empty().with(blob, new byte[65531]).get(blob).length, "the most that fits: 65535 with its header");
        assertThrows(NullPointerException.class, () -> original.with(TRACE, null));
    }

    @Test void malformedBytesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 1, 0}), "truncated header");
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 0, 0, 0}), "key zero");
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 1, 0, 5, 1}), "truncated value");
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[] {0, 1, 0, 0, 0, 1, 0, 0}), "duplicate key");
    }
}
