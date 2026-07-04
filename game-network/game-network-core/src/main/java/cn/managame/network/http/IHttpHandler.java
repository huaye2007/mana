package cn.managame.network.http;

public interface IHttpHandler {

    void onRequest(IHttpExchange exchange) throws Exception;

    default void onException(IHttpExchange exchange, Throwable cause) {
    }
}
