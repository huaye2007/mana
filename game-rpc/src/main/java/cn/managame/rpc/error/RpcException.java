package cn.managame.rpc.error;

/** RPC resource or internal invariant failure. */
public class RpcException extends RuntimeException {
    public RpcException(String message) { super(message); }
    public RpcException(String message, Throwable cause) { super(message, cause); }
}

