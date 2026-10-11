package cn.managame.router.node;

import cn.managame.router.route.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Router bucket: the Nodes registered at that Router and their bindings. Writers are serialized by
 * the bucket owner (the local authority lock or the replica's stream); reads of nodes/bindings are safe
 * concurrently. A published table mirrors every change into the shared RouteIndex.
 */
final class RouterTable {
    static final int MAX_NODES = 100_000, MAX_BINDINGS = 1_000_000;
    final Map<Integer, NodeRegistration> nodes = new ConcurrentHashMap<>();
    final Map<BindingKey, RouteBinding> bindings = new ConcurrentHashMap<>();
    private final Map<Integer, Set<BindingKey>> nodeBindings = new HashMap<>();
    private final int router;
    private RouteIndex index;   // null while staging

    RouterTable() { this(0, null); }
    RouterTable(int router, RouteIndex index) { this.router = router; this.index = index; }

    void add(NodeRegistration n) {
        NodeRegistration old = nodes.get(n.nodeId());
        if (old != null && !old.equals(n)) throw new IllegalArgumentException("Conflicting Node registration");
        if (old == null && nodes.size() >= MAX_NODES) throw new IllegalStateException("Node capacity exceeded");
        if (old != null) return;
        nodes.put(n.nodeId(), n);
        if (index != null) index.addNode(router, n);
    }
    void bind(BindingKey key, RouteBinding owner) {
        NodeRegistration n = nodes.get(owner.nodeId());
        if (n == null || n.nodeEpoch() != owner.nodeEpoch() || n.serviceId() != key.serviceId())
            throw new IllegalArgumentException("Binding owner/service is not registered");
        RouteBinding old = bindings.get(key);
        if (old != null && !old.equals(owner)) throw new IllegalArgumentException("Conflicting binding in one Router bucket");
        if (old != null) return;
        if (bindings.size() >= MAX_BINDINGS) throw new IllegalStateException("Binding capacity exceeded");
        bindings.put(key, owner);
        nodeBindings.computeIfAbsent(owner.nodeId(), ignored -> new HashSet<>()).add(key);
        if (index != null) index.addKey(router, key, owner);
    }
    /** Removes the exact incarnation and every binding it owns. */
    void remove(int id, long epoch) {
        NodeRegistration n = nodes.get(id);
        if (n == null || n.nodeEpoch() != epoch) return;
        Set<BindingKey> keys = nodeBindings.remove(id);
        if (keys != null) for (BindingKey key : keys) {
            RouteBinding owner = bindings.remove(key);
            if (index != null && owner != null) index.removeKey(router, key, owner);
        }
        nodes.remove(id);
        if (index != null) index.removeNode(router, n);
    }
    /** Owner-checked: a stale owner cannot remove a newer incarnation's binding. */
    void unbind(BindingKey key, RouteBinding owner) {
        if (!bindings.remove(key, owner)) return;
        Set<BindingKey> keys = nodeBindings.get(owner.nodeId());
        if (keys != null) { keys.remove(key); if (keys.isEmpty()) nodeBindings.remove(owner.nodeId()); }
        if (index != null) index.removeKey(router, key, owner);
    }
    /** Publishes a staged table, replacing `old` in the index with the minimal set of changes. */
    void publish(RouteIndex target, RouterTable old) {
        for (NodeRegistration n : nodes.values())
            if (old == null || !n.equals(old.nodes.get(n.nodeId()))) target.addNode(router, n);
        for (var e : bindings.entrySet())
            if (old == null || !e.getValue().equals(old.bindings.get(e.getKey()))) target.addKey(router, e.getKey(), e.getValue());
        if (old != null) {
            for (var e : old.bindings.entrySet())
                if (!e.getValue().equals(bindings.get(e.getKey()))) target.removeKey(router, e.getKey(), e.getValue());
            for (NodeRegistration n : old.nodes.values())
                if (!n.equals(nodes.get(n.nodeId()))) target.removeNode(router, n);
            old.index = null;
        }
        index = target;
    }
    /** Removes every entry of this table from its index (Router membership retired). */
    void unpublish() {
        if (index == null) return;
        for (var e : bindings.entrySet()) index.removeKey(router, e.getKey(), e.getValue());
        for (NodeRegistration n : nodes.values()) index.removeNode(router, n);
        index = null;
    }
    void clear() { unpublish(); nodes.clear(); bindings.clear(); nodeBindings.clear(); }
}
