package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class RoundRobinRouter implements Router {
    private final AtomicLong sequence = new AtomicLong();
    private volatile List<ServiceInstance> instances = List.of();

    @Override
    public void refresh(List<ServiceInstance> newInstances) {
        instances = newInstances.stream().filter(ServiceInstance::isHealthy)
                .sorted(Comparator.comparing(ServiceInstance::getKey)).toList();
    }

    @Override
    public ServiceInstance select(long routeKey) {
        List<ServiceInstance> snapshot = instances;
        return snapshot.isEmpty() ? null : snapshot.get((int) Math.floorMod(sequence.getAndIncrement(), snapshot.size()));
    }
}
