package cn.managame.core;

/** Encoded bytes are shared and must be treated as immutable by callers. */
public interface Metadata {
    <T> T get(MetadataKey<T> key);
    default <T> T get(MetadataKey<T> key, T defaultValue) {
        T value = get(key); return value == null ? defaultValue : value;
    }
    boolean contains(MetadataKey<?> key);
    boolean isEmpty();
    byte[] bytes();
}
