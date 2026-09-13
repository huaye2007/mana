package cn.managame.runtime.protocol;

import java.util.*;
/** Immutable registry. Identity is (type, id), never a bare integer. */
public final class ProtocolRegistry {
    public record Identity(ProtocolType type, int id) {}
    private final Map<Class<?>, Protocol> messages;
    private final Map<Identity, Protocol> identities;
    private final Map<Class<?>, Class<?>> responses;
    private ProtocolRegistry(Builder b) {
        messages = Map.copyOf(b.messages); identities = Map.copyOf(b.identities); responses = Map.copyOf(b.responses);
        responses.forEach((req, res) -> {
            if (require(req).type() != ProtocolType.REQUEST || require(res).type() != ProtocolType.RESPONSE)
                throw new IllegalArgumentException("Relationship must connect REQUEST to RESPONSE");
        });
    }
    public Protocol require(Class<?> type) {
        Protocol protocol = messages.get(type);
        if (protocol == null) throw new IllegalArgumentException("Unregistered protocol: " + type);
        return protocol;
    }
    public Protocol require(ProtocolType type, int id) {
        Protocol p = identities.get(new Identity(Objects.requireNonNull(type), id));
        if (p == null) throw new IllegalArgumentException("Unknown protocol: " + type + "/" + id);
        return p;
    }
    public Optional<Protocol> find(Class<?> type) { return Optional.ofNullable(messages.get(type)); }
    /** @deprecated Response relationships belong to the application's message bindings. */
    @Deprecated
    public Optional<Protocol> responseTo(Class<?> request) {
        if (require(request).type() != ProtocolType.REQUEST) throw new IllegalArgumentException("Not a request");
        return Optional.ofNullable(responses.get(request)).map(this::require);
    }
    /** @deprecated Response relationships belong to the application's message bindings. */
    @Deprecated
    public Optional<Protocol> responseTo(Protocol request) {
        if (!require(request.messageType()).equals(request)) throw new IllegalArgumentException("Foreign protocol");
        return responseTo(request.messageType());
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private final Map<Class<?>, Protocol> messages = new HashMap<>();
        private final Map<Identity, Protocol> identities = new HashMap<>();
        private final Map<Class<?>, Class<?>> responses = new HashMap<>();
        public Builder register(int id, ProtocolType type, Class<?> messageType) {
            Protocol p = new Protocol(id, type, messageType);
            Identity identity = new Identity(type, id);
            if (messages.containsKey(messageType) || identities.containsKey(identity))
                throw new IllegalArgumentException("Protocol conflict: " + p);
            messages.put(messageType, p); identities.put(identity, p); return this;
        }
        /** @deprecated Keep request/response relationships in application message bindings. */
        @Deprecated
        public Builder response(Class<?> request, Class<?> response) {
            Objects.requireNonNull(request); Objects.requireNonNull(response);
            if (responses.putIfAbsent(request, response) != null) throw new IllegalArgumentException("Duplicate response relationship");
            return this;
        }
        public ProtocolRegistry build() { return new ProtocolRegistry(this); }
    }
}
