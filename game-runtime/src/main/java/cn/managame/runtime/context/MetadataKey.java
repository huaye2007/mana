package cn.managame.runtime.context;

import java.util.Objects;
/** Declare keys once. Applications own IDs 200..65535; IDs below 200 are reserved. */
public record MetadataKey<T>(int id, Class<T> type) {
    public MetadataKey {
        if (id < 0 || id > 65535) throw new IllegalArgumentException("Metadata ID must be uint16");
        Objects.requireNonNull(type);
        if (type != Integer.class && type != Long.class && type != String.class)
            throw new IllegalArgumentException("Metadata supports INT, LONG and STRING only");
    }
    public static <T> MetadataKey<T> application(int id, Class<T> type) {
        if (id < 200) throw new IllegalArgumentException("IDs 0..199 are reserved");
        return new MetadataKey<>(id, type);
    }
}
