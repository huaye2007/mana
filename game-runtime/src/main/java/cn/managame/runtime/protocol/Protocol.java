package cn.managame.runtime.protocol;

import java.util.Objects;
public record Protocol(int id, ProtocolType type, Class<?> messageType) {
    public Protocol { Objects.requireNonNull(type); Objects.requireNonNull(messageType); }
}
