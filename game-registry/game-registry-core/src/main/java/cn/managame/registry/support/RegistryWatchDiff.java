package cn.managame.registry.support;

import cn.managame.registry.api.DiscoveryEventType;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameEvent;
import cn.managame.registry.api.ServiceNameListener;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

public final class RegistryWatchDiff {

    private RegistryWatchDiff() {
    }

    public static void emitInstanceChanges(
            String serviceName,
            ServiceInstanceListener listener,
            ConcurrentMap<String, ServiceInstance> previous,
            Collection<ServiceInstance> instances,
            Logger logger
    ) {
        Map<String, ServiceInstance> current = instancesByKey(instances);
        for (ServiceInstance instance : current.values()) {
            ServiceInstance old = previous.put(instance.getKey(), instance);
            if (old == null) {
                RegistryListeners.notify(logger, listener,
                        new ServiceInstanceEvent(serviceName, DiscoveryEventType.ADDED, instance));
            } else if (!old.equals(instance)) {
                RegistryListeners.notify(logger, listener,
                        new ServiceInstanceEvent(serviceName, DiscoveryEventType.UPDATED, instance));
            }
        }
        for (String key : new ArrayList<>(previous.keySet())) {
            if (!current.containsKey(key)) {
                ServiceInstance removed = previous.remove(key);
                if (removed != null) {
                    RegistryListeners.notify(logger, listener,
                            new ServiceInstanceEvent(serviceName, DiscoveryEventType.REMOVED, removed));
                }
            }
        }
    }

    public static void emitServiceNameChanges(
            ServiceNameListener listener,
            Set<String> previous,
            Collection<String> names,
            Logger logger
    ) {
        Set<String> current = new HashSet<>(names);
        for (String serviceName : current) {
            if (previous.add(serviceName)) {
                RegistryListeners.notify(logger, listener,
                        new ServiceNameEvent(serviceName, DiscoveryEventType.ADDED));
            }
        }
        for (String serviceName : new ArrayList<>(previous)) {
            if (!current.contains(serviceName) && previous.remove(serviceName)) {
                RegistryListeners.notify(logger, listener,
                        new ServiceNameEvent(serviceName, DiscoveryEventType.REMOVED));
            }
        }
    }

    private static Map<String, ServiceInstance> instancesByKey(Collection<ServiceInstance> instances) {
        Map<String, ServiceInstance> byKey = new HashMap<>();
        if (instances != null) {
            for (ServiceInstance instance : instances) {
                if (instance != null) {
                    byKey.put(instance.getKey(), instance);
                }
            }
        }
        return byKey;
    }
}
