package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Immutable-snapshot consistent hash ring. Reads are lock-free. */
public final class ConsistentHashRouter {
    public static final int DEFAULT_VIRTUAL_NODES = 160;

    private final int virtualNodes;
    private volatile NavigableMap<Long, ServiceInstance> ring = java.util.Collections.emptyNavigableMap();

    public ConsistentHashRouter() { this(DEFAULT_VIRTUAL_NODES); }
    public ConsistentHashRouter(int virtualNodes) {
        if (virtualNodes < 1) throw new IllegalArgumentException("virtualNodes must be positive");
        this.virtualNodes = virtualNodes;
    }

    public void refresh(List<ServiceInstance> instances) {
        List<ServiceInstance> healthy = new ArrayList<>();
        for (ServiceInstance instance : List.copyOf(instances)) if (instance.isHealthy()) healthy.add(instance);
        healthy.sort(Comparator.comparing(ServiceInstance::getKey));
        TreeMap<Long, ServiceInstance> next = new TreeMap<>();
        for (ServiceInstance instance : healthy) {
            int nodes = Math.max(1, (int) Math.round(virtualNodes * Math.min(instance.getWeight(), 20d)));
            for (int i = 0; i < nodes; i++) next.put(hash(instance.getKey() + '#' + i), instance);
        }
        ring = java.util.Collections.unmodifiableNavigableMap(next);
    }

    public ServiceInstance select(long routeKey) {
        NavigableMap<Long, ServiceInstance> snapshot = ring;
        if (snapshot.isEmpty()) return null;
        var entry = snapshot.ceilingEntry(mix64(routeKey));
        return (entry != null ? entry : snapshot.firstEntry()).getValue();
    }

    private static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xffL;
            hash *= 0x100000001b3L;
        }
        return mix64(hash);
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
