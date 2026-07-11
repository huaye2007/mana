package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;

import java.util.List;

public interface Router {
    void refresh(List<ServiceInstance> instances);
    ServiceInstance select(long routeKey);
}
