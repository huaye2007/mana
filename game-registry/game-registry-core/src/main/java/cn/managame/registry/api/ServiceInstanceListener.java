package cn.managame.registry.api;

@FunctionalInterface
public interface ServiceInstanceListener {
    void onEvent(ServiceInstanceEvent event);
}
