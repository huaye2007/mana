package cn.managame.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Typed metadata key: a nonzero uint16 id plus the codec for its value. */
public final class MetadataKey<T> {
    private final int id;
    private final MetadataCodec<T> codec;

    private MetadataKey(int id, MetadataCodec<T> codec) {
        if (id < 1 || id > 65535) throw new IllegalArgumentException("Metadata key must be uint16, nonzero");
        this.id = id; this.codec = Objects.requireNonNull(codec);
    }
    public int id() { return id; }
    MetadataCodec<T> codec() { return codec; }

    public static <T> MetadataKey<T> of(int id, MetadataCodec<T> codec) { return new MetadataKey<>(id, codec); }

    /** One byte: 0 for false, 1 for true. */
    public static MetadataKey<Boolean> booleanKey(int id) {
        return of(id, new MetadataCodec<>() {
            public byte[] encode(Boolean value) { return new byte[]{(byte) (value ? 1 : 0)}; }
            public Boolean decode(byte[] bytes, int offset, int length) {
                if (length != 1) throw new IllegalArgumentException("Expected one-byte boolean");
                return switch (bytes[offset]) {
                    case 0 -> false;
                    case 1 -> true;
                    default -> throw new IllegalArgumentException("Expected boolean value 0 or 1");
                };
            }
        });
    }
    /** Big-endian int64. */
    public static MetadataKey<Long> longKey(int id) {
        return of(id, new MetadataCodec<>() {
            public byte[] encode(Long v) { return ByteBuffer.allocate(8).putLong(v).array(); }
            public Long decode(byte[] b, int o, int n) {
                if (n != 8) throw new IllegalArgumentException("Expected int64");
                return ByteBuffer.wrap(b, o, n).getLong();
            }
        });
    }
    /** Big-endian int32. */
    public static MetadataKey<Integer> intKey(int id) {
        return of(id, new MetadataCodec<>() {
            public byte[] encode(Integer v) { return ByteBuffer.allocate(4).putInt(v).array(); }
            public Integer decode(byte[] b, int o, int n) {
                if (n != 4) throw new IllegalArgumentException("Expected int32");
                return ByteBuffer.wrap(b, o, n).getInt();
            }
        });
    }
    /** UTF-8 text. */
    public static MetadataKey<String> stringKey(int id) {
        return of(id, new MetadataCodec<>() {
            public byte[] encode(String v) { return v.getBytes(StandardCharsets.UTF_8); }
            public String decode(byte[] b, int o, int n) { return new String(b, o, n, StandardCharsets.UTF_8); }
        });
    }
    /** Raw bytes; decoding copies the value. */
    public static MetadataKey<byte[]> bytesKey(int id) {
        return of(id, new MetadataCodec<>() {
            public byte[] encode(byte[] v) { return v; }
            public byte[] decode(byte[] b, int o, int n) { return Arrays.copyOfRange(b, o, o + n); }
        });
    }
}
