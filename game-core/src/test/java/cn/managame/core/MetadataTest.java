package cn.managame.core;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class MetadataTest {
    @Test void booleanValuesHaveCanonicalBytesAndWorkAlongsideIntegers() {
        var number = MetadataKey.intKey(1);
        var disabled = MetadataKey.booleanKey(258);
        var enabled = MetadataKey.booleanKey(259);
        var metadata = Metadata.builder().put(number, 0x01020304)
            .put(disabled, false).put(enabled, true).build();
        assertArrayEquals(new byte[]{
            0, 1, 0, 4, 1, 2, 3, 4,
            1, 2, 0, 1, 0,
            1, 3, 0, 1, 1
        }, metadata.bytes());
        var wrapped = Metadata.wrap(metadata.bytes());
        assertEquals(0x01020304, wrapped.get(number));
        assertEquals(Boolean.FALSE, wrapped.get(disabled));
        assertEquals(Boolean.TRUE, wrapped.get(enabled));
        assertEquals(Boolean.TRUE, wrapped.get(MetadataKey.booleanKey(259)));
    }

    @Test void booleanDefaultsDistinguishMissingFromFalseAndRejectNull() {
        var flag = MetadataKey.booleanKey(1);
        var missing = MetadataKey.booleanKey(2);
        var metadata = Metadata.builder().put(flag, false).build();
        assertTrue(metadata.contains(flag));
        assertFalse(metadata.get(flag, true));
        assertFalse(metadata.contains(missing));
        assertNull(metadata.get(missing));
        assertTrue(metadata.get(missing, true));
        assertFalse(metadata.get(missing, false));
        assertThrows(NullPointerException.class, () -> Metadata.builder().put(flag, null));
        assertThrows(IllegalArgumentException.class, () -> MetadataKey.booleanKey(0));
        assertThrows(IllegalArgumentException.class, () -> MetadataKey.booleanKey(65536));
    }

    @Test void booleanRejectsMalformedValuesOnlyWhenDecoded() {
        var flag = MetadataKey.booleanKey(1);
        for (byte[] invalid : new byte[][]{new byte[0], {0, 1}}) {
            var metadata = Metadata.builder().put(MetadataKey.bytesKey(1), invalid).build();
            assertTrue(metadata.contains(flag));
            assertThrows(IllegalArgumentException.class, () -> metadata.get(flag));
        }
        for (int value = 2; value <= 255; value++) {
            byte[] encoded = {0, 1, 0, 1, (byte) value};
            var metadata = assertDoesNotThrow(() -> Metadata.wrap(encoded));
            assertArrayEquals(new byte[]{(byte) value}, metadata.get(MetadataKey.bytesKey(1)));
            assertThrows(IllegalArgumentException.class, () -> metadata.get(flag, false));
        }
    }

    @Test void intKeysPreserveSignedBoundariesAndRejectWrongWidth() {
        var key = MetadataKey.intKey(1);
        for (int value : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}) {
            var metadata = Metadata.builder().put(key, value).build();
            assertEquals(value, Metadata.wrap(metadata.bytes()).get(key));
        }
        var wrongWidth = Metadata.builder().put(MetadataKey.bytesKey(1), new byte[3]).build();
        assertThrows(IllegalArgumentException.class, () -> wrongWidth.get(key));
    }

    @Test void lazyCodecAndSharedBacking() {
        AtomicInteger decodes=new AtomicInteger();
        var custom=MetadataKey.of(65535, new MetadataCodec<String>() {
            public byte[] encode(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
            public String decode(byte[] b,int o,int n) { decodes.incrementAndGet(); return new String(b,o,n,java.nio.charset.StandardCharsets.UTF_8); }
        });
        var trace=MetadataKey.longKey(1);
        var metadata=Metadata.builder().put(trace, -1L).put(custom, "中文").build();
        byte[] bytes=metadata.bytes(); var wrapped=Metadata.wrap(bytes);
        assertSame(bytes, wrapped.bytes()); assertEquals(0,decodes.get());
        assertEquals(-1L,wrapped.get(trace)); assertEquals("中文",wrapped.get(custom)); assertEquals(1,decodes.get());
        assertEquals(7,wrapped.get(MetadataKey.intKey(99),7));
    }
    @Test void rejectsCorruptionDuplicatesAndBounds() {
        for (byte[] invalid : new byte[][]{{0},{0,1,0},{0,0,0,0},{0,1,0,2,1},{0,1,0,0,0,1,0,0}})
            assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(invalid));
        assertThrows(IllegalArgumentException.class, () -> MetadataKey.longKey(0));
        assertThrows(IllegalArgumentException.class, () -> MetadataKey.longKey(65536));
        assertThrows(IllegalArgumentException.class, () -> Metadata.wrap(new byte[65536]));
        assertThrows(IllegalArgumentException.class, () -> Metadata.builder().put(MetadataKey.bytesKey(1),new byte[65532]).build());
    }
    @Test void knownEndianVectorAndBuilderSnapshots() {
        var key=MetadataKey.intKey(258);
        var builder=Metadata.builder().put(key,0x01020304);
        var first=builder.build();
        assertArrayEquals(new byte[]{1,2,0,4,1,2,3,4},first.bytes());
        builder.put(key,9);
        assertEquals(0x01020304,first.get(key)); assertEquals(9,builder.build().get(key));
        assertThrows(IllegalArgumentException.class, () -> first.get(MetadataKey.longKey(258)));
    }
}
