package cn.managame.router.node;

import cn.managame.router.route.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lock-free lookup view merged from the local bucket and every Router replica.
 * Each bucket contributes at most one candidate per Node ID and per binding key; candidates are tagged
 * with the bucket's Router ID so buckets never overwrite each other. Writers update one key atomically
 * (ConcurrentHashMap.compute); readers see immutable candidate arrays and never block.
 */
final class RouteIndex {
    /** A Node registration as published by one Router bucket. */
    record NodeEntry(int router, NodeRegistration node) {}
    /** A binding as published by one Router bucket. */
    record KeyEntry(int router, int nodeId, long nodeEpoch) {}
    /** Resolved delivery target. */
    record Target(int router, int nodeId, long nodeEpoch, int serviceId) {}

    private static final NodeEntry[] NO_NODES = {};
    private static final KeyEntry[] NO_KEYS = {};
    final ConcurrentHashMap<Integer, NodeEntry[]> nodes = new ConcurrentHashMap<>();
    final ConcurrentHashMap<BindingKey, KeyEntry[]> keys = new ConcurrentHashMap<>();

    void addNode(int router, NodeRegistration n) {
        NodeEntry entry = new NodeEntry(router, n);
        nodes.compute(n.nodeId(), (id, old) -> put(old == null ? NO_NODES : old, entry, e -> e.router == router, NodeEntry[]::new));
    }
    void removeNode(int router, NodeRegistration n) {
        nodes.computeIfPresent(n.nodeId(), (id, old) -> remove(old, e -> e.router == router && e.node.equals(n), NodeEntry[]::new));
    }
    void addKey(int router, BindingKey key, RouteBinding owner) {
        KeyEntry entry = new KeyEntry(router, owner.nodeId(), owner.nodeEpoch());
        keys.compute(key, (k, old) -> put(old == null ? NO_KEYS : old, entry, e -> e.router == router, KeyEntry[]::new));
    }
    void removeKey(int router, BindingKey key, RouteBinding owner) {
        keys.computeIfPresent(key, (k, old) -> remove(old,
                e -> e.router == router && e.nodeId == owner.nodeId() && e.nodeEpoch == owner.nodeEpoch(), KeyEntry[]::new));
    }

    /** Winner among buckets: largest unsigned nodeEpoch, then smallest unsigned Router ID. */
    NodeEntry locate(int nodeId) {
        NodeEntry[] candidates = nodes.get(nodeId);
        if (candidates == null) return null;
        NodeEntry best = null;
        for (NodeEntry c : candidates) {
            if (best == null) { best = c; continue; }
            int epoch = Long.compareUnsigned(c.node.nodeEpoch(), best.node.nodeEpoch());
            if (epoch > 0 || (epoch == 0 && Integer.compareUnsigned(c.router, best.router) < 0)) best = c;
        }
        return best;
    }
    /**
     * A binding is valid only while its owner's located incarnation is exactly that epoch and serves the
     * key's service. Conflicting valid owners resolve to the smallest unsigned nodeId, then largest epoch.
     */
    Target resolve(BindingKey key) {
        KeyEntry[] candidates = keys.get(key);
        if (candidates == null) return null;
        Target best = null;
        for (KeyEntry c : candidates) {
            NodeEntry located = locate(c.nodeId);
            if (located == null || located.node.nodeEpoch() != c.nodeEpoch || located.node.serviceId() != key.serviceId()) continue;
            if (best == null || Integer.compareUnsigned(c.nodeId, best.nodeId) < 0
                    || (c.nodeId == best.nodeId && Long.compareUnsigned(c.nodeEpoch, best.nodeEpoch) > 0))
                best = new Target(located.router, c.nodeId, c.nodeEpoch, key.serviceId());
        }
        return best;
    }
    /** Number of distinct valid owners currently claiming the key (diagnostics for partition conflicts). */
    int owners(BindingKey key) {
        KeyEntry[] candidates = keys.get(key);
        if (candidates == null) return 0;
        Set<Long> owners = new HashSet<>();
        for (KeyEntry c : candidates) {
            NodeEntry located = locate(c.nodeId);
            if (located != null && located.node.nodeEpoch() == c.nodeEpoch) owners.add(((long) c.nodeId << 32) ^ c.nodeEpoch);
        }
        return owners.size();
    }
    void clear() { nodes.clear(); keys.clear(); }

    private static <T> T[] put(T[] old, T entry, java.util.function.Predicate<T> same, java.util.function.IntFunction<T[]> array) {
        for (int i = 0; i < old.length; i++) if (same.test(old[i])) {
            if (old[i].equals(entry)) return old;
            T[] copy = old.clone(); copy[i] = entry; return copy;
        }
        T[] copy = Arrays.copyOf(old, old.length + 1); copy[old.length] = entry; return copy;
    }
    private static <T> T[] remove(T[] old, java.util.function.Predicate<T> match, java.util.function.IntFunction<T[]> array) {
        for (int i = 0; i < old.length; i++) if (match.test(old[i])) {
            if (old.length == 1) return null;
            T[] copy = array.apply(old.length - 1);
            System.arraycopy(old, 0, copy, 0, i); System.arraycopy(old, i + 1, copy, i, old.length - i - 1);
            return copy;
        }
        return old;
    }
}
