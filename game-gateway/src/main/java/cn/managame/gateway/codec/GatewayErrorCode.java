package cn.managame.gateway.codec;

/** Stable error codes emitted by the gateway itself. */
public final class GatewayErrorCode {
    public static final int OK = 0;
    public static final int INTERNAL_ERROR = 1;
    public static final int BAD_REQUEST = 3;
    public static final int NOT_LOGGED_IN = 4;
    public static final int SERVER_BUSY = 5;
    public static final int DUPLICATE_LOGIN = 6;
    public static final int NO_BACKEND = 100;
    public static final int RATE_LIMITED = 101;
    public static final int CONNECTION_LIMITED = 102;
    public static final int GATEWAY_ERROR = 103;

    private GatewayErrorCode() { }
}
