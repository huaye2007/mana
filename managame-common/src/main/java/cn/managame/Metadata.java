package cn.managame;

import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validated, encoded metadata: a sequence of {@code uint16 key, uint16 length, value} entries with
 * nonzero, unique keys and at most 65535 bytes in total. Values are decoded lazily by their key.
 * The encoded bytes are shared and must be treated as immutable.
 */
public final class Metadata {
    private static final Metadata EMPTY = new Metadata(new byte[0]);
    private final byte[] bytes;

    private Metadata(byte[] bytes) { this.bytes = bytes; }

    public static Metadata empty() { return EMPTY; }

    /** Validates and wraps encoded bytes without copying them. */
    public static Metadata wrap(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length > 65535) throw new IllegalArgumentException("Metadata exceeds uint16 length");
        BitSet keys = new BitSet();
        for (int p = 0; p < bytes.length;) {
            if (bytes.length - p < 4) throw new IllegalArgumentException("Truncated metadata header");
            int key = u16(bytes, p), length = u16(bytes, p + 2);
            if (key == 0 || keys.get(key)) throw new IllegalArgumentException("Invalid or duplicate metadata key");
            keys.set(key); p += 4;
            if (length > bytes.length - p) throw new IllegalArgumentException("Truncated metadata value");
            p += length;
        }
        return bytes.length == 0 ? EMPTY : new Metadata(bytes);
    }

    public static Builder builder() { return new Builder(); }

    public <T> T get(MetadataKey<T> key) {
        int p = offset(key.id());
        return p < 0 ? null : key.codec().decode(bytes, p + 4, u16(bytes, p + 2));
    }
    public <T> T get(MetadataKey<T> key, T defaultValue) {
        T value = get(key); return value == null ? defaultValue : value;
    }
    public boolean contains(MetadataKey<?> key) { return offset(key.id()) >= 0; }
    public boolean isEmpty() { return bytes.length == 0; }
    public byte[] bytes() { return bytes; }

    private int offset(int key) {
        for (int p = 0; p < bytes.length; p += 4 + u16(bytes, p + 2))
            if (u16(bytes, p) == key) return p;
        return -1;
    }
    private static int u16(byte[] b, int p) { return (b[p] & 255) << 8 | b[p + 1] & 255; }

    /** Mutable assembly; each build() returns an independent snapshot. A repeated key keeps the last value. */
    public static final class Builder {
        private final Map<Integer, byte[]> values = new LinkedHashMap<>();
        private Builder() {}
        public <T> Builder put(MetadataKey<T> key, T value) {
            byte[] encoded = Objects.requireNonNull(key.codec().encode(Objects.requireNonNull(value)));
            if (encoded.length > 65535) throw new IllegalArgumentException("Value too large");
            values.put(key.id(), encoded); return this;
        }
        public Metadata build() {
            long length = values.values().stream().mapToLong(v -> 4L + v.length).sum();
            if (length > 65535) throw new IllegalArgumentException("Metadata too large");
            ByteBuffer b = ByteBuffer.allocate((int) length);
            values.forEach((k, v) -> b.putShort(k.shortValue()).putShort((short) v.length).put(v));
            return wrap(b.array());
        }
    }
}
