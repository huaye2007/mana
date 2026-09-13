package cn.managame.rpc.protocol;

/**
 * Invalid envelope. A codec may attach correlation fields after reading a complete fixed header.
 */
public final class RpcProtocolException extends RuntimeException {
    private final int requestId;
    private final boolean response;
    private final long routeKey;

    public RpcProtocolException(String message) {
        this(message, null);
    }

    public RpcProtocolException(String message, Throwable cause) {
        this(message, cause, 0, false, 0);
    }

    public RpcProtocolException(
            String message, Throwable cause, int requestId, boolean response, long routeKey) {
        super(message, cause);
        this.requestId = requestId;
        this.response = response;
        this.routeKey = routeKey;
    }

    public int requestId() {
        return requestId;
    }

    public boolean isResponse() {
        return response;
    }

    public long routeKey() {
        return routeKey;
    }
}
