package cn.managame.core;

import java.util.Objects;
public final class MetadataKey<T> {
    private final int id;
    private final MetadataCodec<T> codec;
    MetadataKey(int id, MetadataCodec<T> codec) {
        if (id < 1 || id > 65535) throw new IllegalArgumentException("Metadata key must be uint16, nonzero");
        this.id = id; this.codec = Objects.requireNonNull(codec);
    }
    public int id() { return id; }
    MetadataCodec<T> codec() { return codec; }
}
