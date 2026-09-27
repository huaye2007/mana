package cn.managame.runtime.protocol;

import java.util.Objects;
public final class Protocols {
    private Protocols() {}
    private record Descriptor<T>(ProtocolType type, int command, Class<T> messageType) implements ProtocolDescriptor<T> {
        Descriptor { Objects.requireNonNull(type); Objects.requireNonNull(messageType); }
    }
    public static <T> ProtocolDescriptor<T> request(int command, Class<T> type) { return new Descriptor<>(ProtocolType.REQUEST, command, type); }
    public static <T> ProtocolDescriptor<T> response(int command, Class<T> type) { return new Descriptor<>(ProtocolType.RESPONSE, command, type); }
    public static <T> ProtocolDescriptor<T> notify(int command, Class<T> type) { return new Descriptor<>(ProtocolType.NOTIFY, command, type); }
}
