package cn.managame.network;

/** Synchronous component lifecycle. Do not call from network callbacks. */
public interface NetworkServer {
    void start();

    void stop();
}
