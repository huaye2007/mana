package cn.managame.runtime.context;

import java.util.Arrays;
import java.util.Objects;
/** Immutable sparse metadata. Each update returns a new value, safe across routes. */
public final class Metadata {
    private static final Metadata EMPTY = new Metadata(new MetadataKey<?>[0], new Object[0]);
    private final MetadataKey<?>[] keys;
    private final Object[] values;
    private Metadata(MetadataKey<?>[] keys, Object[] values) { this.keys = keys; this.values = values; }
    public static Metadata empty() { return EMPTY; }
    public int size() { return keys.length; }
    public <T> T get(MetadataKey<T> key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].id() == key.id()) {
            checkType(keys[i], key);
            return key.type().cast(values[i]);
        }
        return null;
    }
    public <T> Metadata with(MetadataKey<T> key, T value) {
        Objects.requireNonNull(key); key.type().cast(Objects.requireNonNull(value));
        for (int i = 0; i < keys.length; i++) if (keys[i].id() == key.id()) {
            checkType(keys[i], key);
            Object[] copy = values.clone(); copy[i] = value;
            return new Metadata(keys, copy);
        }
        MetadataKey<?>[] nextKeys = Arrays.copyOf(keys, keys.length + 1);
        Object[] nextValues = Arrays.copyOf(values, values.length + 1);
        nextKeys[keys.length] = key; nextValues[values.length] = value;
        return new Metadata(nextKeys, nextValues);
    }
    private static void checkType(MetadataKey<?> a, MetadataKey<?> b) {
        if (a.type() != b.type()) throw new IllegalArgumentException("Conflicting metadata type for ID " + b.id());
    }
}
