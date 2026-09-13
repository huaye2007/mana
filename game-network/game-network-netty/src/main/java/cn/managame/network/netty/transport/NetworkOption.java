package cn.managame.network.netty.transport;

import java.util.function.Predicate;

/** Component options only. Native network settings use ChannelOption directly. */
public final class NetworkOption<T> {
    final String name;
    final Class<T> type;
    final T defaultValue;
    final Predicate<T> valid;

    NetworkOption(String name, Class<T> type, T value, Predicate<T> valid) {
        this.name = name;
        this.type = type;
        defaultValue = value;
        this.valid = valid;
    }

    void validate(Object value) {
        if (value == null || !type.isInstance(value) || !valid.test(type.cast(value)))
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
    }

    @Override
    public String toString() {
        return name;
    }
}
