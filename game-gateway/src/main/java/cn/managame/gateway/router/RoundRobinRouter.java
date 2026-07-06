package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡：忽略 routeKey，按序均摊到各实例。
 * 适合无状态后端；有玩家态的游戏服建议用 {@link ConsistentHashRouter}
 * （粘滞由 BackendRouterManager 的会话绑定兜底，本策略只决定首包落点）。
 */
public class RoundRobinRouter implements Router {

    private volatile ServiceInstance[] instances = new ServiceInstance[0];
    private final AtomicInteger cursor = new AtomicInteger();

    @Override
    public void refresh(List<ServiceInstance> newInstances) {
        instances = newInstances.toArray(new ServiceInstance[0]);
    }

    @Override
    public ServiceInstance select(long routeKey) {
        ServiceInstance[] snapshot = instances;
        if (snapshot.length == 0) {
            return null;
        }
        int index = Math.floorMod(cursor.getAndIncrement(), snapshot.length);
        return snapshot[index];
    }
}
