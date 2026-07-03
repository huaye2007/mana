package com.github.huaye2007.mana.registry.api;

/**
 * Callback for service-instance changes from {@link Discovery#watchService}.
 * <p>
 * Threading contract: {@code onEvent} is invoked directly on the backend's event-dispatch thread
 * (e.g. the etcd gRPC callback thread, the Nacos notifier thread, the Curator event thread, or the
 * Consul watch loop thread). The very first events are also delivered <em>synchronously</em> inside
 * the {@code watchService(...)} call while it emits the initial snapshot. Implementations must not
 * block (no remote calls, locks held long, or unbounded work) and must not throw — a slow listener
 * stalls the backend's event delivery for every watcher sharing that thread. Hand work off to your
 * own executor/thread group if you need to do anything non-trivial.
 */
@FunctionalInterface
public interface ServiceInstanceListener {
    void onEvent(ServiceInstanceEvent event);
}
