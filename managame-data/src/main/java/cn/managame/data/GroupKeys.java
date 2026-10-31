package cn.managame.data;

import java.util.Arrays;
import java.util.Objects;

/**
 * The key of a group: the values of its entities' {@code @GroupKey} fields, in their order, such as
 * {@code GroupKeys.of(playerId)}. Equal values make equal keys.
 */
public final class GroupKeys {
    private final Object[] values;
    private final int hash;

    private GroupKeys(Object[] values) {
        if (values.length == 0) throw new IllegalArgumentException("a group key needs at least one value");
        for (Object value : values) Objects.requireNonNull(value, "group key value");
        this.values = values;
        hash = Arrays.hashCode(values);
    }

    /** The key made of {@code values}; the array is kept, so do not change it afterwards. */
    public static GroupKeys of(Object... values) { return new GroupKeys(values); }

    public int size() { return values.length; }

    public Object valueAt(int index) { return values[index]; }

    @Override public int hashCode() { return hash; }

    @Override public boolean equals(Object other) {
        return other instanceof GroupKeys key && hash == key.hash && Arrays.equals(values, key.values);
    }

    @Override public String toString() { return Arrays.toString(values); }
}
