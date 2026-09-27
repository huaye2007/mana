package cn.managame.data.key;

import java.util.Arrays;
import java.util.Objects;

/** Ordered, typed identity. The supplied array and its values must never be mutated. */
public final class GroupKey {
    private final Object[] values;
    private final int hash;
    private GroupKey(Object[] values) {
        if (values.length == 0) throw new IllegalArgumentException("Empty GroupKey");
        for (Object value : values) Objects.requireNonNull(value, "GroupKey component");
        this.values = values;
        hash = Arrays.hashCode(values);
    }
    public static GroupKey of(Object... values) { return new GroupKey(values); }
    public int size() { return values.length; }
    public Object valueAt(int index) { return values[index]; }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return other instanceof GroupKey key && Arrays.equals(values, key.values);
    }
    @Override public String toString() { return Arrays.toString(values); }
}
