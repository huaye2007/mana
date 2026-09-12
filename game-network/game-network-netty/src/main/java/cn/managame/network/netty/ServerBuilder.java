package cn.managame.network.netty;

import io.netty.channel.ChannelOption;

import java.util.*;

/** Common listen addresses, resources and native listener/child options. */
abstract class ServerBuilder<B extends ServerBuilder<B>> extends OptionsBuilder<B> {
    final Map<String, java.net.InetSocketAddress> addresses = new LinkedHashMap<>();
    final Map<ChannelOption<?>, Object> listenerOptions = new LinkedHashMap<>();
    NetworkResources resources;

    public B resources(NetworkResources value) {
        resources = Objects.requireNonNull(value);
        return self();
    }

    public B listen(String host, int port) {
        return listen(new java.net.InetSocketAddress(host, port));
    }

    public B listen(java.net.InetSocketAddress address) {
        return listen(address.toString(), address);
    }

    public B listen(String name, java.net.InetSocketAddress address) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Listener name required");
        if (addresses.putIfAbsent(name, Objects.requireNonNull(address)) != null)
            throw new IllegalArgumentException("Duplicate listener: " + name);
        return self();
    }

    @Override
    public <T> B option(ChannelOption<T> key, T value) {
        Objects.requireNonNull(key).validate(value);
        listenerOptions.put(key, value);
        return self();
    }

    public <T> B childOption(ChannelOption<T> key, T value) {
        return super.option(key, value);
    }
}
