package cn.managame.gateway.router;

import cn.managame.gateway.session.GatewaySession;
import cn.managame.registry.api.ServiceInstance;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Isolates instance rings by logical service name. */
public final class BackendDirectory {
    private final Supplier<Router> routerFactory;
    private final ConcurrentHashMap<String, BackendRouterManager> services = new ConcurrentHashMap<>();

    public BackendDirectory(Supplier<Router> routerFactory) {
        this.routerFactory = Objects.requireNonNull(routerFactory, "routerFactory");
    }

    public BackendRouterManager service(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("serviceName must not be blank");
        return services.computeIfAbsent(serviceName, ignored ->
                new BackendRouterManager(Objects.requireNonNull(routerFactory.get(), "routerFactory result")));
    }

    public ServiceInstance resolve(String serviceName, GatewaySession session) {
        return service(serviceName).resolve(session, serviceName);
    }

    public int serviceCount() { return services.size(); }
}
