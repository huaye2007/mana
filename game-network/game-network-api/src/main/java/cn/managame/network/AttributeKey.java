package cn.managame.network;

import java.util.Objects;

/** Keys use object identity; equal names do not alias. */
public final class AttributeKey<T> {
    private final String name;
    private final Class<T> type;
    private final String id = java.util.UUID.randomUUID().toString();

    private AttributeKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
    }

    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type);
    }

    public T cast(Object value) {
        return type.cast(value);
    }

    /** Stable identity when adapting this key to a native attribute facility. */
    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return name;
    }
}
