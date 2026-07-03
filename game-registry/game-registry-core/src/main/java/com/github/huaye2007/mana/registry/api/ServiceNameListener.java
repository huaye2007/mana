package com.github.huaye2007.mana.registry.api;

/**
 * Callback for service-name changes from {@link Discovery#watchServiceNames}.
 * <p>
 * Threading contract: {@code onEvent} is invoked directly on the backend's event-dispatch thread
 * (or the registry's internal polling thread for the Nacos polling fallback). The initial set of
 * names is also delivered <em>synchronously</em> inside the {@code watchServiceNames(...)} call.
 * Implementations must not block and must not throw — a slow listener stalls event delivery for
 * other watchers. Hand off non-trivial work to your own executor/thread group.
 */
@FunctionalInterface
public interface ServiceNameListener {
    void onEvent(ServiceNameEvent event);
}
