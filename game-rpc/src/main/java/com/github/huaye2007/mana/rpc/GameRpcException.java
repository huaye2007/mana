package com.github.huaye2007.mana.rpc;

/**
 * RPC 调用失败：超时、连接不可用、对端返回非 0 code 等。
 */
public class GameRpcException extends RuntimeException {

    private final int code;

    public GameRpcException(String message) {
        super(message);
        this.code = -1;
    }

    public GameRpcException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public GameRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
