package cn.managame.registry.support;

import cn.managame.registry.api.ServiceInstanceEvent;
import cn.managame.registry.api.ServiceInstanceListener;
import cn.managame.registry.api.ServiceNameEvent;
import cn.managame.registry.api.ServiceNameListener;
import org.slf4j.Logger;

/**
 * Delivers discovery events to listeners, swallowing and logging any {@link RuntimeException} the
 * listener throws so one misbehaving listener cannot break the backend's event-dispatch thread.
 * <p>
 * See the threading contract on {@link ServiceInstanceListener} / {@link ServiceNameListener}.
 */
public final class RegistryListeners {

    private RegistryListeners() {
    }

    public static void notify(Logger logger, ServiceInstanceListener listener, ServiceInstanceEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            logger.warn("Service instance listener failed for service {}", event.getServiceName(), e);
        }
    }

    public static void notify(Logger logger, ServiceNameListener listener, ServiceNameEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            logger.warn("Service name listener failed for service {}", event.getServiceName(), e);
        }
    }
}
