package cn.managame.router.node;

import cn.managame.router.route.*;
import java.util.*;

/** Mutations and reads are serialized by RouterEngine's monitor. Buckets remain authoritative. */
final class RouterTable {
    static final int MAX_NODES = 100_000, MAX_BINDINGS = 1_000_000;
    final Map<Integer, NodeRegistration> nodes = new HashMap<>();
    final Map<BindingKey, RouteBinding> bindings = new HashMap<>();
    private final Map<Integer, Set<BindingKey>> nodeBindings = new HashMap<>();
    void add(NodeRegistration n) {
        NodeRegistration old = nodes.get(n.nodeId());
        if (old != null && !old.equals(n)) throw new IllegalArgumentException("Conflicting Node registration");
        if (old == null && nodes.size() >= MAX_NODES) throw new IllegalStateException("Node capacity exceeded");
        nodes.put(n.nodeId(), n);
    }
    void bind(BindingKey key, RouteBinding owner) {
        NodeRegistration n = nodes.get(owner.nodeId());
        if (n == null || n.nodeEpoch() != owner.nodeEpoch() || n.serviceId() != key.serviceId())
            throw new IllegalArgumentException("Binding owner/service is not registered");
        RouteBinding old = bindings.get(key);
        if (old != null && !old.equals(owner)) throw new IllegalArgumentException("Conflicting binding in one Router bucket");
        if (old == null && bindings.size() >= MAX_BINDINGS) throw new IllegalStateException("Binding capacity exceeded");
        bindings.put(key, owner);
        nodeBindings.computeIfAbsent(owner.nodeId(), ignored -> new HashSet<>()).add(key);
    }
    void remove(int id, long epoch) {
        NodeRegistration n = nodes.get(id);
        if (n == null || n.nodeEpoch() != epoch) return;
        nodes.remove(id);
        Set<BindingKey> keys = nodeBindings.remove(id);
        if (keys != null) keys.forEach(bindings::remove);
    }
    void unbind(BindingKey key, RouteBinding owner) {
        if (bindings.remove(key, owner)) {
            Set<BindingKey> keys = nodeBindings.get(owner.nodeId());
            if (keys != null) { keys.remove(key); if (keys.isEmpty()) nodeBindings.remove(owner.nodeId()); }
        }
    }
    void clear() { nodes.clear(); bindings.clear(); nodeBindings.clear(); }
}
