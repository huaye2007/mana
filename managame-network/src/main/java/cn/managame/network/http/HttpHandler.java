package cn.managame.network.http;

/** Handles every request of an HTTP listener under its context path, answering through the exchange now or later. */
@FunctionalInterface
public interface HttpHandler {
    /**
     * Runs on the connection's EventLoop and must not block. The request is released when this returns, so
     * retain it to use it afterwards. Every request needs exactly one response; until it is written the
     * connection reads no further request. An exception is answered with 500.
     */
    void handle(HttpExchange exchange) throws Exception;
}
