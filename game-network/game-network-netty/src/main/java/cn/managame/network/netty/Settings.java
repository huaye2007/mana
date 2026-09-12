package cn.managame.network.netty;

import io.netty.channel.ChannelOption;

import java.util.Map;
import java.util.Set;

/** Immutable option snapshot. Protocol configuration belongs to each concrete component. */
final class Settings {
    final Map<ChannelOption<?>, Object> nativeOptions;
    private final Map<NetworkOption<?>, Object> options;

    Settings(OptionsBuilder<?> builder, NetworkOption<?>... supportedOptions) {
        var supported = Set.of(supportedOptions);
        for (var key : builder.options.keySet())
            if (!supported.contains(key))
                throw new IllegalArgumentException(
                        key + " is not supported by " + builder.getClass().getName());
        nativeOptions = Map.copyOf(builder.nativeOptions);
        options = Map.copyOf(builder.options);
    }

    <T> T get(NetworkOption<T> key) {
        return key.type.cast(options.getOrDefault(key, key.defaultValue));
    }
}
