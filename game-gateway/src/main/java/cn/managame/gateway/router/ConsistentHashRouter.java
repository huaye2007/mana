package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一致性哈希路由：虚拟节点环，同 routeKey 稳定命中同一实例，
 * 实例上下线只影响环上相邻区段的键（重登陆的玩家大概率还回到原服）。
 *
 * <p>环在 {@link #refresh} 时整体重建为不可变 TreeMap 后 volatile 替换，
 * {@link #select} 只读快照，无锁。</p>
 */
public class ConsistentHashRouter implements Router {

    /** 每个实例的虚拟节点数：够大才能摊平少量实例时的哈希倾斜。 */
    public static final int DEFAULT_VIRTUAL_NODES = 160;

    private final int virtualNodes;

    private volatile TreeMap<Long, ServiceInstance> ring = new TreeMap<>();

    public ConsistentHashRouter() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    public ConsistentHashRouter(int virtualNodes) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException("virtualNodes must be > 0, got " + virtualNodes);
        }
        this.virtualNodes = virtualNodes;
    }

    @Override
    public void refresh(List<ServiceInstance> instances) {
        TreeMap<Long, ServiceInstance> newRing = new TreeMap<>();
        for (ServiceInstance instance : instances) {
            String nodeKey = instance.getKey();
            for (int i = 0; i < virtualNodes; i++) {
                newRing.put(hash(nodeKey + "#" + i), instance);
            }
        }
        ring = newRing;
    }

    @Override
    public ServiceInstance select(long routeKey) {
        TreeMap<Long, ServiceInstance> snapshot = ring;
        if (snapshot.isEmpty()) {
            return null;
        }
        Map.Entry<Long, ServiceInstance> entry = snapshot.ceilingEntry(hash(Long.toString(routeKey)));
        if (entry == null) {
            entry = snapshot.firstEntry(); // 环回绕
        }
        return entry.getValue();
    }

    /**
     * FNV-1a 64 累积 + MurmurHash3 fmix64 收尾。裸 FNV 对短、相近的 key
     * （如 {@code node#0..#159}、顺序数字）雪崩性差，会让环弧长严重不均、少数节点吃掉大部分流量；
     * fmix64 收尾把每比特充分打散，vnode 与 routeKey 都能均匀落环。无外部依赖。
     */
    static long hash(String key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xffL;
            h *= 0x100000001b3L;
        }
        // MurmurHash3 fmix64 finalizer
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
