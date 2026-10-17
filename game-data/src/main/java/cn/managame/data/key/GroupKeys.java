package cn.managame.data.key;

import java.util.Arrays;
import java.util.Objects;

/**
 * An ordered, typed group identity: the values of an entity's {@code @GroupKey} fields, in declaration
 * order. The supplied array and its values must never be mutated.
 *
 * <p>Named in the plural like {@link MapKeys}, because the {@code key} package holds the key helpers and
 * {@code GroupKey} itself is the field annotation an entity declares.
 */
public final class GroupKeys {
    private final Object[] values;
    private final int hash;
    private GroupKeys(Object[] values) {
        if (values.length == 0) throw new IllegalArgumentException("Empty group key");
        for (Object value : values) Objects.requireNonNull(value, "group key component");
        this.values = values;
        hash = Arrays.hashCode(values);
    }
    public static GroupKeys of(Object... values) { return new GroupKeys(values); }
    public int size() { return values.length; }
    public Object valueAt(int index) { return values[index]; }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return other instanceof GroupKeys key && Arrays.equals(values, key.values);
    }
    @Override public String toString() { return Arrays.toString(values); }
}
