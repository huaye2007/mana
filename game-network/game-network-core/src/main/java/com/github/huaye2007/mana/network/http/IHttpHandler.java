package com.github.huaye2007.mana.network.http;

public interface IHttpHandler {

    void onRequest(IHttpExchange exchange) throws Exception;

    default void onException(IHttpExchange exchange, Throwable cause) {
    }
}
