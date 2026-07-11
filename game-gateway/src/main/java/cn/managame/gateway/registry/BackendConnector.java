package cn.managame.gateway.registry;

import cn.managame.registry.api.ServiceInstance;

/** Narrow seam between discovery and the RPC connection pool. */
public interface BackendConnector {
    void connectBackend(ServiceInstance instance);
    void disconnectBackend(ServiceInstance instance);
}
