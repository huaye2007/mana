package cn.managame.rpc;

public final class RpcException extends RuntimeException {
    // Only built-in framework signals are cached; never retain arbitrary business codes globally.
    private static final RpcException[] SIGNALS = {
        null,
        new RpcException(RpcError.NO_HANDLER, false),
        new RpcException(RpcError.DECODE_ERROR, false),
        new RpcException(RpcError.INTERNAL_ERROR, false),
        new RpcException(RpcError.OVERLOADED, false),
        new RpcException(RpcError.UNAVAILABLE, false),
        new RpcException(RpcError.TIMEOUT, false),
        new RpcException(RpcError.PROTOCOL_ERROR, false)
    };
    static final RpcException UNAVAILABLE = signal(RpcError.UNAVAILABLE);
    static final RpcException OVERLOADED = signal(RpcError.OVERLOADED);

    static RpcException signal(RpcError error) {
        int code = error.code();
        return code < SIGNALS.length ? SIGNALS[code] : new RpcException(error, false);
    }

    private final RpcError error;

    public RpcException(RpcError error) {
        this(error, true);
    }

    private RpcException(RpcError error, boolean diagnostic) {
        super(error.message(), null, diagnostic, diagnostic);
        this.error = error;
    }

    public RpcError error() {
        return error;
    }
}
