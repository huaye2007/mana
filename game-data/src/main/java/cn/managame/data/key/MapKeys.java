package cn.managame.data.key;

import java.util.Objects;
import java.util.StringJoiner;

public final class MapKeys {
    private MapKeys() {}
    /** Composite keys accept integral values and strings without ':'. No escaping. */
    public static String of(Object... values) {
        if (values.length < 2) throw new IllegalArgumentException("Composite MapKey requires at least two values");
        StringJoiner key = new StringJoiner(":");
        for (Object value : values) {
            Objects.requireNonNull(value, "MapKey component");
            if (!(value instanceof String || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long))
                throw new IllegalArgumentException("Unsupported MapKey component: " + value.getClass());
            String part = value.toString();
            if (part.indexOf(':') >= 0) throw new IllegalArgumentException("MapKey component contains ':'");
            key.add(part);
        }
        return key.toString();
    }
}
