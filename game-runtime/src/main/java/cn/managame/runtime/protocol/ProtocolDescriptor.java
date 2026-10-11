package cn.managame.runtime.protocol;

import java.util.Objects;

/**
 * One message protocol: direction, command number and message class. A REQUEST may name its response
 * class. Each direction has its own command space, so a response may reuse its request's command or not.
 */
public record ProtocolDescriptor(Type type, int command, Class<?> messageType, Class<?> responseType) {
    public enum Type { REQUEST, RESPONSE, NOTIFY }

    public ProtocolDescriptor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(messageType, "messageType");
        if (responseType != null && type != Type.REQUEST)
            throw new IllegalArgumentException("Only a REQUEST has a response type: " + messageType);
    }
    /** A request without a response, such as an RPC notify. */
    public static ProtocolDescriptor request(int command, Class<?> messageType) {
        return new ProtocolDescriptor(Type.REQUEST, command, messageType, null);
    }
    /** A request answered by {@code responseType}, which must also be registered as a RESPONSE. */
    public static ProtocolDescriptor request(int command, Class<?> messageType, Class<?> responseType) {
        return new ProtocolDescriptor(Type.REQUEST, command, messageType, Objects.requireNonNull(responseType, "responseType"));
    }
    public static ProtocolDescriptor response(int command, Class<?> messageType) {
        return new ProtocolDescriptor(Type.RESPONSE, command, messageType, null);
    }
    public static ProtocolDescriptor notify(int command, Class<?> messageType) {
        return new ProtocolDescriptor(Type.NOTIFY, command, messageType, null);
    }
}
