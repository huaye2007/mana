package cn.managame.rpc.error;

/** Encoding failed before network admission; the outbound body has already been consumed. */
public final class RpcEncodeException extends RpcException {
    public RpcEncodeException(String message) { super(message); }
    public RpcEncodeException(String message, Throwable cause) { super(message, cause); }
}

