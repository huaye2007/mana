package cn.managame.registry.api;

public interface Registry extends AutoCloseable {
    void register(ServiceInstance serviceInstance);

    void unregister(ServiceInstance serviceInstance);

    void start();

    @Override
    void close();
}
