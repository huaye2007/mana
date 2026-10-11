package cn.managame.runtime.protocol;

import java.util.*;

/**
 * Immutable, validated protocol table. Each (type, command) and each message class is registered once,
 * and every request's response type is registered as a RESPONSE. Lookups are thread-safe.
 */
public final class ProtocolRegistry {
    private static final ProtocolRegistry EMPTY = new ProtocolRegistry(List.of());
    private record Key(ProtocolDescriptor.Type type, int command) {}
    private final List<ProtocolDescriptor> descriptors;
    private final Map<Key, ProtocolDescriptor> byCommand = new HashMap<>();
    private final Map<Class<?>, ProtocolDescriptor> byClass = new HashMap<>();

    private ProtocolRegistry(List<ProtocolDescriptor> descriptors) {
        this.descriptors = List.copyOf(descriptors);
        for (ProtocolDescriptor d : this.descriptors) {
            if (byCommand.putIfAbsent(new Key(d.type(), d.command()), d) != null)
                throw new IllegalArgumentException("Duplicate protocol " + d.type() + " " + d.command());
            if (byClass.putIfAbsent(d.messageType(), d) != null)
                throw new IllegalArgumentException("Duplicate protocol class " + d.messageType().getName());
        }
        for (ProtocolDescriptor d : this.descriptors) {
            if (d.responseType() == null) continue;
            ProtocolDescriptor response = byClass.get(d.responseType());
            if (response == null || response.type() != ProtocolDescriptor.Type.RESPONSE)
                throw new IllegalArgumentException("Response type of " + d.messageType().getName()
                        + " must be registered as RESPONSE: " + d.responseType().getName());
        }
    }
    public static ProtocolRegistry empty() { return EMPTY; }
    public static ProtocolRegistry of(ProtocolDescriptor... descriptors) { return of(Arrays.asList(descriptors)); }
    public static ProtocolRegistry of(Collection<ProtocolDescriptor> descriptors) {
        return descriptors.isEmpty() ? EMPTY : new ProtocolRegistry(new ArrayList<>(descriptors));
    }

    public ProtocolDescriptor get(ProtocolDescriptor.Type type, int command) { return byCommand.get(new Key(type, command)); }
    public ProtocolDescriptor get(Class<?> messageType) { return byClass.get(messageType); }
    /** Registration order; use with {@link #of(Collection)} to merge registries. */
    public List<ProtocolDescriptor> descriptors() { return descriptors; }
}
