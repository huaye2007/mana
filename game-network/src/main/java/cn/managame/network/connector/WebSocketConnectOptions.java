package cn.managame.network.connector;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import java.util.Objects;

/** Immutable per-connect header snapshot and optional subprotocol. */
public final class WebSocketConnectOptions {
    private final HttpHeaders headers;
    private final String subprotocol;

    public WebSocketConnectOptions(HttpHeaders headers, String subprotocol) {
        this.headers = Objects.requireNonNull(headers, "headers").copy();
        this.subprotocol = subprotocol;
    }
    public static WebSocketConnectOptions headers(HttpHeaders headers) {
        return new WebSocketConnectOptions(headers, null);
    }
    public static WebSocketConnectOptions of(HttpHeaders headers, String subprotocol) {
        return new WebSocketConnectOptions(headers, subprotocol);
    }
    public static WebSocketConnectOptions defaults() {
        return new WebSocketConnectOptions(new DefaultHttpHeaders(), null);
    }
    public HttpHeaders headers() { return headers.copy(); }
    public String subprotocol() { return subprotocol; }
}