package cn.managame.data;

import java.util.Objects;

/** Map keys made of several {@code @MapKey} fields, which a group's map is keyed by as one String. */
public final class MapKeys {
    private MapKeys() {}

    /**
     * The values in order, joined by {@code ':'}, such as {@code "3:12"}. Each value is integral or a String
     * without {@code ':'}; there is no escaping.
     */
    public static String of(Object... values) {
        if (values.length < 2) throw new IllegalArgumentException("a composite map key needs at least two values");
        StringBuilder key = new StringBuilder();
        for (Object value : values) {
            Objects.requireNonNull(value, "map key value");
            if (!(value instanceof String || value instanceof Long || value instanceof Integer
                    || value instanceof Short || value instanceof Byte))
                throw new IllegalArgumentException("map key values are integral or String: " + value.getClass().getName());
            String part = value.toString();
            if (part.indexOf(':') >= 0) throw new IllegalArgumentException("map key value contains ':': " + part);
            if (!key.isEmpty()) key.append(':');
            key.append(part);
        }
        return key.toString();
    }
}
