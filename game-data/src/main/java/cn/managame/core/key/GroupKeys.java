package cn.managame.core.key;

import java.util.Objects;
import java.util.StringJoiner;

/** Builds a native single-field key or a colon-separated composite string. */
public final class GroupKeys {
    private GroupKeys() { }

    public static Object of(Object... values) {
        Objects.requireNonNull(values, "values");
        if (values.length == 0) throw new IllegalArgumentException("A key requires at least one value");
        if (values.length == 1) return Objects.requireNonNull(values[0], "key value");
        StringJoiner key = new StringJoiner(":");
        for (Object value : values) {
            Objects.requireNonNull(value, "key value");
            String part = value instanceof Enum<?> e ? e.name() : value.toString();
            if (part.indexOf(':') >= 0) throw new IllegalArgumentException("Composite key values must not contain ':'");
            key.add(part);
        }
        return key.toString();
    }
}
