package cn.managame.rpc.protocol;

import java.util.Objects;

/** Immutable error identity. Codes 1..1000 belong to RPC; business constants start at 1001. */
public final class RpcError {
    public static final int MAX_INTERNAL_CODE = 1000;
    public static final RpcError NO_HANDLER = new RpcError(1, "No registered handler", true);
    public static final RpcError DECODE_ERROR = new RpcError(2, "Message decode failed", true);
    public static final RpcError INTERNAL_ERROR = new RpcError(3, "Internal RPC error", true);
    public static final RpcError OVERLOADED = new RpcError(4, "RPC capacity exceeded", true);
    public static final RpcError UNAVAILABLE =
            new RpcError(5, "Node or connection unavailable", true);
    public static final RpcError TIMEOUT = new RpcError(6, "RPC deadline elapsed", true);
    public static final RpcError PROTOCOL_ERROR = new RpcError(7, "Invalid RPC protocol", true);

    private final int code;
    private final String message;

    /** Defines a business error constant; the framework range is reserved. */
    public RpcError(int code, String message) {
        this(code, message, false);
    }

    private RpcError(int code, String message, boolean internalOrReceived) {
        if (code <= 0 || (!internalOrReceived && code <= MAX_INTERNAL_CODE))
            throw new IllegalArgumentException(
                    "Business error code must be > " + MAX_INTERNAL_CODE);
        this.code = code;
        this.message = Objects.requireNonNull(message, "message");
    }

    public int code() {
        return code;
    }

    /** Local description, not transmitted. Unknown received codes have an empty description. */
    public String message() {
        return message;
    }

    /** Preserves every positive wire code, including reserved codes from newer peers. */
    public static RpcError fromCode(int code) {
        if (code <= 0) throw new RpcProtocolException("Invalid RPC error code: " + code);
        return switch (code) {
            case 1 -> NO_HANDLER;
            case 2 -> DECODE_ERROR;
            case 3 -> INTERNAL_ERROR;
            case 4 -> OVERLOADED;
            case 5 -> UNAVAILABLE;
            case 6 -> TIMEOUT;
            case 7 -> PROTOCOL_ERROR;
            default -> new RpcError(code, "", true);
        };
    }

    /** Descriptions may differ across locales or be absent on the wire; identity is the code. */
    @Override
    public boolean equals(Object other) {
        return other instanceof RpcError error && code == error.code;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(code);
    }

    @Override
    public String toString() {
        return "RpcError[code=" + code + ", message=" + message + "]";
    }
}
