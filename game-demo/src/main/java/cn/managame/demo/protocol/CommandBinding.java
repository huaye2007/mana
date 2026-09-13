package cn.managame.demo.protocol;

import java.util.Objects;

/** Shared protocol declaration; no serialization, network or runtime dependencies. */
public record CommandBinding<Q, S>(int id, Class<Q> requestType, Class<S> responseType) {
    public CommandBinding {
        if (id == 0) throw new IllegalArgumentException("Command ID must not be zero");
        Objects.requireNonNull(requestType);
        Objects.requireNonNull(responseType);
    }

}
