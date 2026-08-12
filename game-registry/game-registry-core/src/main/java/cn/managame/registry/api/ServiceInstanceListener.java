package cn.managame.registry.api;

/**
 * Receives discovery events for a watched service.
 *
 * <p><strong>Called synchronously by the provider.</strong> The initial
 * snapshot arrives on the thread that called
 * {@link ServiceRegistry#watchService(String, ServiceInstanceListener)}; incremental events arrive
 * on whichever thread the provider delivers them on — the caller that triggered the change for the
 * in-memory registry, a Nacos notifier thread, or a jetcd gRPC callback thread.
 *
 * <p>An implementation must therefore return quickly: no blocking IO, no lock waits, no long
 * computation, and no calls back into the same {@link ServiceRegistry} (which may reenter or
 * deadlock). Hand that work to a thread you own.
 *
 * <p>Exceptions thrown here are logged and dropped by the provider. They are not retried and never
 * propagate to the caller. There is no error channel: a silent listener may mean there are no
 * instances, or that the underlying watch is reconnecting in the background.
 */
@FunctionalInterface
public interface ServiceInstanceListener {
    void onEvent(ServiceInstanceEvent event);
}
