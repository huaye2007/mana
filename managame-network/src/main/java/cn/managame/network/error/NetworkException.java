package cn.managame.network.error;

/** Bind, connect, handshake or infrastructure shutdown failure; preserves its underlying cause. */
public class NetworkException extends RuntimeException {
    public NetworkException(String message) { super(message); }
    public NetworkException(String message, Throwable cause) { super(message, cause); }
}