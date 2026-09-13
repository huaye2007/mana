package cn.managame.rpc.protocol;

import io.netty.buffer.*;

import java.nio.charset.*;
import java.util.*;

/**
 * Primitive byte encoders/decoders. Key meanings are defined externally, not registered with RPC.
 */
public final class RpcMetadataUtil {
    private RpcMetadataUtil() {}

    static final class Storage {
        final int maximum;
        final boolean readOnly;
        ByteBuf buffer;

        Storage(int maximum) {
            if (maximum < 0) throw new IllegalArgumentException("metadata limit");
            this.maximum = maximum;
            readOnly = false;
            buffer = heap(Math.min(128, maximum), maximum);
        }

        Storage(ByteBuf bytes) {
            buffer = bytes.asReadOnly();
            maximum = bytes.readableBytes();
            readOnly = true;
        }
    }

    private static ByteBuf heap(int initial, int maximum) {
        return Unpooled.unreleasableBuffer(Unpooled.buffer(initial, maximum));
    }

    static Storage emptyStorage() {
        return new Storage(Unpooled.EMPTY_BUFFER);
    }

    static RpcMetadata mutableCopy(RpcMetadata source, int maximum) {
        if (source.encodedLength() > maximum) throw new IllegalArgumentException("Metadata limit");
        var copy = new RpcMetadata(maximum);
        writeTo(source, copy.storage.buffer);
        return copy;
    }

    public static RpcMetadata snapshot(RpcMetadata metadata) {
        if (metadata.storage.readOnly) return metadata;
        if (encodedLength(metadata) == 0) return RpcMetadata.EMPTY;
        var owned = heap(encodedLength(metadata), encodedLength(metadata));
        writeTo(metadata, owned);
        return new RpcMetadata(new Storage(owned));
    }

    static int encodedLength(RpcMetadata metadata) {
        return metadata.storage.buffer.readableBytes();
    }

    // Stored entries are validated on insertion or decode; sending only checks the node limit.
    public static void validateSize(RpcMetadata metadata, int maximum) {
        if (maximum < 0 || encodedLength(metadata) > maximum)
            throw new RpcProtocolException("Metadata limit");
    }

    static void writeTo(RpcMetadata metadata, ByteBuf output) {
        output.writeBytes(metadata.storage.buffer, 0, encodedLength(metadata));
    }

    static ByteBuf encoded(RpcMetadata metadata) {
        return metadata.storage.buffer.slice(0, encodedLength(metadata)).asReadOnly();
    }

    private static int find(RpcMetadata metadata, short key) {
        if (key <= 0) throw new IllegalArgumentException("Invalid key");
        var bytes = metadata.storage.buffer;
        for (int p = 0; p < encodedLength(metadata); p += 3 + bytes.getByte(p + 2))
            if (bytes.getShort(p) == key) return p;
        return -1;
    }

    public static boolean contains(RpcMetadata metadata, short key) {
        return find(metadata, key) >= 0;
    }

    public static ByteBuf getBuffer(RpcMetadata metadata, short key) {
        int p = find(metadata, key);
        return p < 0
                ? null
                : metadata.storage
                        .buffer
                        .slice(p + 3, metadata.storage.buffer.getByte(p + 2))
                        .asReadOnly();
    }

    public static byte[] get(RpcMetadata metadata, short key) {
        var value = getBuffer(metadata, key);
        if (value == null) return null;
        var result = new byte[value.readableBytes()];
        value.readBytes(result);
        return result;
    }

    static RpcMetadata copyOf(ByteBuf source, int maximum) {
        var input = source.slice(source.readerIndex(), source.readableBytes());
        validate(input, maximum);
        if (!input.isReadable()) return RpcMetadata.EMPTY;
        var owned = heap(input.readableBytes(), input.readableBytes());
        owned.writeBytes(input);
        return new RpcMetadata(new Storage(owned));
    }

    static void validate(ByteBuf source, int maximum) {
        var b = source.slice(source.readerIndex(), source.readableBytes());
        int length = b.readableBytes();
        if (maximum < 0 || length > maximum) throw new RpcProtocolException("Metadata limit");
        if (length == 0) return;
        // Keep the common small header entirely in local primitives, including on virtual threads.
        long firstKeys = 0, nextKeys = 0;
        int keyCount = 0;
        BitSet keys = null;
        for (int p = 0; p < length; ) {
            if (length - p < 3) throw new RpcProtocolException("Truncated metadata header");
            short key = b.getShort(p);
            int n = b.getByte(p + 2);
            if (key <= 0 || n < 0 || n > length - p - 3)
                throw new RpcProtocolException("Invalid metadata key/length");
            if (keys == null) {
                for (int i = 0; i < keyCount; i++) {
                    long packed = i < 4 ? firstKeys : nextKeys;
                    if (((packed >>> ((i & 3) * 16)) & 0xffffL) == key)
                        throw new RpcProtocolException("Duplicate metadata key");
                }
                if (keyCount < 4) firstKeys |= (long) key << (keyCount * 16);
                else if (keyCount < 8) nextKeys |= (long) key << ((keyCount - 4) * 16);
                else {
                    // Only larger headers allocate a bitmap. Never cache it on a thread.
                    keys = new BitSet(Short.MAX_VALUE + 1);
                    for (int i = 0; i < 8; i++) {
                        long packed = i < 4 ? firstKeys : nextKeys;
                        keys.set((int) ((packed >>> ((i & 3) * 16)) & 0xffffL));
                    }
                    keys.set(key);
                }
                keyCount++;
            } else {
                if (keys.get(key)) throw new RpcProtocolException("Duplicate metadata key");
                keys.set(key);
            }
            p += 3 + n;
        }
    }

    private static ByteBuf reserve(RpcMetadata metadata, short key, int n) {
        var storage = metadata.storage;
        if (storage.readOnly)
            throw new UnsupportedOperationException("Metadata snapshot is read-only");
        if (key <= 0) throw new IllegalArgumentException("Invalid key");
        if (n < 0 || n > 127)
            throw new IllegalArgumentException("Metadata value exceeds 127 bytes");
        if (find(metadata, key) >= 0) throw new IllegalArgumentException("Duplicate key");
        var buffer = storage.buffer;
        if (3 + n > storage.maximum - buffer.writerIndex())
            throw new IllegalArgumentException("Metadata limit");
        buffer.ensureWritable(3 + n);
        buffer.writeShort(key).writeByte(n);
        return buffer;
    }

    public static RpcMetadata put(RpcMetadata metadata, short key, byte[] value) {
        Objects.requireNonNull(value);
        reserve(metadata, key, value.length).writeBytes(value);
        return metadata;
    }

    public static RpcMetadata put(RpcMetadata metadata, short key, ByteBuf value) {
        Objects.requireNonNull(value);
        reserve(metadata, key, value.readableBytes())
                .writeBytes(value, value.readerIndex(), value.readableBytes());
        return metadata;
    }

    private static int requireOffset(RpcMetadata metadata, short key, int length) {
        int p = find(metadata, key);
        if (p < 0) throw new NoSuchElementException("Missing metadata key " + key);
        if (length >= 0 && metadata.storage.buffer.getByte(p + 2) != length)
            throw new IllegalArgumentException("Invalid metadata value length for key " + key);
        return p + 3;
    }

    private static ByteBuf require(RpcMetadata metadata, short key, int length) {
        int p = requireOffset(metadata, key, length);
        return metadata.storage
                .buffer
                .slice(p, metadata.storage.buffer.getByte(p - 1))
                .asReadOnly();
    }

    public static int getInt(RpcMetadata metadata, short key) {
        return metadata.storage.buffer.getInt(requireOffset(metadata, key, 4));
    }

    public static long getLong(RpcMetadata metadata, short key) {
        return metadata.storage.buffer.getLong(requireOffset(metadata, key, 8));
    }

    public static boolean getBoolean(RpcMetadata metadata, short key) {
        byte value = metadata.storage.buffer.getByte(requireOffset(metadata, key, 1));
        if (value != 0 && value != 1) throw new IllegalArgumentException("Boolean must be 0 or 1");
        return value == 1;
    }

    public static String getString(RpcMetadata metadata, short key) {
        var value = require(metadata, key, -1);
        if (!ByteBufUtil.isText(value, StandardCharsets.UTF_8))
            throw new IllegalArgumentException("Invalid UTF-8 value for key " + key);
        return value.toString(StandardCharsets.UTF_8);
    }

    public static RpcMetadata putInt(RpcMetadata metadata, short key, int value) {
        var buffer = reserve(metadata, key, 4);
        buffer.writeInt(value);
        return metadata;
    }

    public static RpcMetadata putLong(RpcMetadata metadata, short key, long value) {
        var buffer = reserve(metadata, key, 8);
        buffer.writeLong(value);
        return metadata;
    }

    public static RpcMetadata putBoolean(RpcMetadata metadata, short key, boolean value) {
        var buffer = reserve(metadata, key, 1);
        buffer.writeByte((byte) (value ? 1 : 0));
        return metadata;
    }

    public static RpcMetadata putString(RpcMetadata metadata, short key, String value) {
        Objects.requireNonNull(value);
        // Bound before encoding as well; valid UTF-8 has at least one byte per UTF-16 unit.
        if (value.length() > 127)
            throw new IllegalArgumentException("Metadata value exceeds 127 bytes");
        int size = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
                    throw new IllegalArgumentException("Invalid surrogate");
                size += 4;
            } else if (Character.isLowSurrogate(ch))
                throw new IllegalArgumentException("Invalid surrogate");
            else size += ch < 0x80 ? 1 : ch < 0x800 ? 2 : 3;
        }
        var buffer = reserve(metadata, key, size);
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x80) buffer.writeByte((byte) cp);
            else if (cp < 0x800)
                buffer.writeByte((byte) (0xc0 | (cp >> 6))).writeByte((byte) (0x80 | (cp & 63)));
            else if (cp < 0x10000)
                buffer.writeByte((byte) (0xe0 | (cp >> 12)))
                        .writeByte((byte) (0x80 | ((cp >> 6) & 63)))
                        .writeByte((byte) (0x80 | (cp & 63)));
            else
                buffer.writeByte((byte) (0xf0 | (cp >> 18)))
                        .writeByte((byte) (0x80 | ((cp >> 12) & 63)))
                        .writeByte((byte) (0x80 | ((cp >> 6) & 63)))
                        .writeByte((byte) (0x80 | (cp & 63)));
        }
        return metadata;
    }

    public static RpcMetadata.Builder putInt(RpcMetadata.Builder builder, short key, int value) {
        putInt(builder.metadata, key, value);
        return builder;
    }

    public static RpcMetadata.Builder putLong(RpcMetadata.Builder builder, short key, long value) {
        putLong(builder.metadata, key, value);
        return builder;
    }

    public static RpcMetadata.Builder putString(
            RpcMetadata.Builder builder, short key, String value) {
        putString(builder.metadata, key, value);
        return builder;
    }

    public static RpcMetadata.Builder putBoolean(
            RpcMetadata.Builder builder, short key, boolean value) {
        putBoolean(builder.metadata, key, value);
        return builder;
    }
}
