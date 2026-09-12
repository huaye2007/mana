package cn.managame.network.netty;

import io.netty.channel.ChannelOption;

import java.util.*;

/** Shared option collection for protocol builders. */
abstract class OptionsBuilder<B extends OptionsBuilder<B>> {
    final Map<ChannelOption<?>, Object> nativeOptions = new LinkedHashMap<>();
    final Map<NetworkOption<?>, Object> options = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    final B self() {
        return (B) this;
    }

    public <T> B option(ChannelOption<T> key, T value) {
        Objects.requireNonNull(key).validate(value);
        nativeOptions.put(key, value);
        return self();
    }

    public <T> B option(NetworkOption<T> key, T value) {
        Objects.requireNonNull(key).validate(value);
        options.put(key, value);
        return self();
    }
}
