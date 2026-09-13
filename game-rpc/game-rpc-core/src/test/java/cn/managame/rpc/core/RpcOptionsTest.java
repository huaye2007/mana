package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;

import static cn.managame.rpc.protocol.RpcMetadataUtil.*;
import static cn.managame.rpc.core.RpcTestSupport.*;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.*;

import org.junit.jupiter.api.Test;

import java.nio.ReadOnlyBufferException;

class RpcOptionsTest {
    static final short LONG = 1024, STRING = 1025, BYTES = 1026;

    @Test
    void metadataFacadeDelegatesPutGetAndOptionsOwnReadOnlySnapshots() {
        var metadata =
                new RpcMetadata()
                        .putInt((short) 1, Integer.MIN_VALUE)
                        .putLong(LONG, 123L)
                        .putString(STRING, "trace")
                        .putBoolean((short) 2, true)
                        .put(BYTES, new byte[] {1, 2});
        assertEquals(Integer.MIN_VALUE, metadata.getInt((short) 1));
        assertEquals(123L, metadata.getLong(LONG));
        assertEquals("trace", metadata.getString(STRING));
        assertTrue(metadata.getBoolean((short) 2));
        assertArrayEquals(new byte[] {1, 2}, metadata.get(BYTES));
        var builder = RpcOptions.builder().metadata(metadata);
        var options = builder.build();
        assertNotSame(metadata, options.metadata());
        for (short key = 2000; key < 2100; key++) metadata.putInt(key, key);
        assertEquals(2099, metadata.getInt((short) 2099));
        assertFalse(options.metadata().contains((short) 2000));
        assertTrue(builder.build().metadata().contains((short) 2000));
        assertEquals("trace", options.metadata().getString(STRING));
        assertThrows(
                UnsupportedOperationException.class,
                () -> options.metadata().putInt((short) 2000, 1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> RpcMetadata.EMPTY.putBoolean((short) 1, true));
        assertThrows(IllegalArgumentException.class, () -> metadata.putLong(LONG, 4));
        assertEquals(123L, metadata.getLong(LONG));
        var snapshot =
                RpcMetadata.builder().putInt((short) 1, 7).putBoolean((short) 2, false).build();
        assertEquals(7, snapshot.getInt((short) 1));
        assertFalse(snapshot.getBoolean((short) 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RpcMetadata(3).putBoolean((short) 1, true));
    }

    @Test
    void directOptionsMatchExistingWireAndOwnTheirValues() {
        var input = raw(new byte[] {9, 1, 2, 9}).setIndex(1, 3);
        var builder =
                RpcOptions.builder().putLong(LONG, -123).putString(STRING, "中😀").put(BYTES, input);
        var metadata = builder.build().metadata();
        assertEquals(1, input.readerIndex());
        input.setByte(1, (byte) 8);
        var expected =
                RpcOptions.builder()
                        .putLong(LONG, -123)
                        .putString(STRING, "中😀")
                        .put(BYTES, raw(new byte[] {1, 2}))
                        .build()
                        .metadata();
        assertEquals(expected.encoded(), metadata.encoded());
        assertEquals(-123, getLong(metadata, LONG));
        assertEquals("中😀", getString(metadata, STRING));
        assertThrows(IllegalArgumentException.class, () -> getString(metadata, LONG));
        assertThrows(ReadOnlyBufferException.class, () -> metadata.encoded().setByte(0, 0));
        builder.putString((short) 1027, "later");
        assertFalse(metadata.contains((short) 1027));
        var wire = new RpcTestSupport.TestCodec(ALLOCATOR, RpcLimits.DEFAULT);
        var frame =
                wire.request(
                        1,
                        1,
                        new RpcOptions(0, (byte) 0, 0, metadata, null),
                        Unpooled.EMPTY_BUFFER);
        var decoded = (RpcRequest) wire.decode(frame);
        assertEquals(expected.encoded(), decoded.metadata().encoded());
        assertEquals("中😀", getString(decoded.metadata(), STRING));
    }

    @Test
    void existingMetadataCanBeReusedAppendedOrReplacedWithoutLosingUnknownKeys() {
        assertSame(RpcMetadata.EMPTY, RpcOptions.builder().build().metadata());
        var original = RpcMetadata.copyOf(raw(new byte[] {0x13, (byte) 0x88, 2, 1, 2}), 100);
        var builder = RpcOptions.builder().metadata(original);
        assertSame(original, builder.build().metadata());
        builder.putLong(LONG, 3);
        var appended = builder.build().metadata();
        assertEquals(original.encoded(), appended.encoded().slice(0, original.encodedLength()));
        assertEquals(3, getLong(appended, LONG));
        assertFalse(original.contains(LONG));
        assertThrows(
                IllegalArgumentException.class, () -> builder.putString((short) 5000, "duplicate"));
        assertSame(original, builder.metadata(original).build().metadata());
        assertSame(RpcMetadata.EMPTY, builder.metadata(RpcMetadata.EMPTY).build().metadata());
        var local = RpcOptions.builder().putString(STRING, "trace").build().metadata();
        var copy = RpcOptions.builder().metadata(local).putLong(LONG, 4).build().metadata();
        assertEquals("trace", getString(copy, STRING));
        assertEquals(4, getLong(copy, LONG));
    }

    @Test
    void invalidWritesLeaveBuilderUsableAndLimitsUseEncodedBytes() {
        var builder = RpcOptions.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.putLong((short) 0, 1));
        assertThrows(IllegalArgumentException.class, () -> builder.putLong((short) -1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.putString(STRING, "中".repeat(42) + "ab"));
        assertThrows(IllegalArgumentException.class, () -> builder.putString(STRING, "\ud800"));
        assertThrows(IllegalArgumentException.class, () -> builder.put(BYTES, raw(new byte[128])));
        assertSame(RpcMetadata.EMPTY, builder.build().metadata());
        builder.putString(STRING, "中".repeat(41) + "abcd");
        assertThrows(IllegalArgumentException.class, () -> builder.putLong(STRING, 1));
        assertEquals(130, builder.build().metadata().encodedLength());
        for (short key = 2000; key < 2125; key++) builder.put(key, raw(new byte[127]));
        assertEquals(16380, builder.build().metadata().encodedLength());
        assertThrows(IllegalArgumentException.class, () -> builder.putLong(LONG, 1));
        var finalMetadata = builder.putString(LONG, "a").build().metadata();
        assertEquals(16384, finalMetadata.encodedLength());
        assertEquals("a", getString(finalMetadata, LONG));
        assertEquals("中".repeat(41) + "abcd", getString(finalMetadata, STRING));
    }

    @Test
    void rawValuesHaveNoTypeTagsAndRespectNodeLimits() {
        var value = new byte[] {(byte) 0xff, 2, 3};
        var options = RpcOptions.builder().put(LONG, value).build();
        value[0] = 0;
        assertArrayEquals(new byte[] {(byte) 0xff, 2, 3}, options.metadata().get(LONG));
        var copy = options.metadata().get(LONG);
        copy[0] = 1;
        assertEquals((byte) 0xff, options.metadata().getBuffer(LONG).readByte());
        assertThrows(
                ReadOnlyBufferException.class,
                () -> options.metadata().getBuffer(LONG).setByte(0, 1));
        assertThrows(IllegalArgumentException.class, () -> getLong(options.metadata(), LONG));
        assertNull(options.metadata().get(STRING));
        assertNull(options.metadata().getBuffer(STRING));
        assertArrayEquals(
                new byte[0],
                RpcOptions.builder().put(STRING, new byte[0]).build().metadata().get(STRING));
        assertThrows(
                RpcProtocolException.class,
                () -> RpcMetadata.copyOf(options.metadata().encoded(), 5));
        assertDoesNotThrow(() -> RpcMetadata.copyOf(options.metadata().encoded(), 6));
    }

    @Test
    void primitiveHelpersUseOrdinaryBytesAndBooleanRejectsNonCanonicalValuesOnRead() {
        var meta =
                RpcOptions.builder()
                        .putInt((short) 1, Integer.MIN_VALUE)
                        .putLong((short) 2, Long.MAX_VALUE)
                        .putBoolean((short) 3, true)
                        .putBoolean((short) 4, false)
                        .putString((short) 5, "trace")
                        .build()
                        .metadata();
        assertArrayEquals(new byte[] {(byte) 0x80, 0, 0, 0}, meta.get((short) 1));
        assertArrayEquals(new byte[] {1}, meta.get((short) 3));
        assertArrayEquals(new byte[] {0}, meta.get((short) 4));
        assertEquals(Integer.MIN_VALUE, getInt(meta, (short) 1));
        assertEquals(Long.MAX_VALUE, getLong(meta, (short) 2));
        assertTrue(getBoolean(meta, (short) 3));
        assertFalse(getBoolean(meta, (short) 4));
        assertEquals("trace", getString(meta, (short) 5));
        var raw =
                RpcMetadata.builder()
                        .put((short) 1, new byte[] {0, 0, 0, 7})
                        .put((short) 2, new byte[] {1})
                        .put((short) 3, new byte[] {2})
                        .build();
        assertEquals(7, getInt(raw, (short) 1));
        assertTrue(getBoolean(raw, (short) 2));
        assertArrayEquals(new byte[] {2}, raw.get((short) 3));
        assertThrows(IllegalArgumentException.class, () -> getBoolean(raw, (short) 3));
        assertThrows(IllegalArgumentException.class, () -> getBoolean(raw, (short) 1));
        assertThrows(java.util.NoSuchElementException.class, () -> getBoolean(raw, (short) 4));
    }
}
