package cn.managame.rpc;

import java.util.Objects;

/**
 * Received responses are preserved, including business errors. Local failures have no value.
 *
 * @param value complete received response, or null for local failures
 * @param error framework or business error; null only for a successful response
 */
public record RpcResult(RpcResponse value, RpcError error) {
    public RpcResult {
        if (value == null) Objects.requireNonNull(error, "error");
        else if (value.errorCode() == 0
                ? error != null
                : error == null || error.code() != value.errorCode())
            throw new IllegalArgumentException("Response/error mismatch");
    }

    public boolean isSuccess() {
        return value != null && value.errorCode() == 0;
    }

    /** The remote code, or the local RPC failure code when no response arrived. */
    public int errorCode() {
        return value == null ? error.code() : value.errorCode();
    }

    public static RpcResult received(RpcResponse response) {
        Objects.requireNonNull(response, "response");
        return new RpcResult(
                response,
                response.errorCode() == 0 ? null : RpcError.fromCode(response.errorCode()));
    }

    public static RpcResult success(RpcResponse value) {
        Objects.requireNonNull(value, "value");
        if (value.errorCode() != 0)
            throw new IllegalArgumentException("Expected successful response");
        return received(value);
    }

    public static RpcResult failure(RpcError error) {
        return new RpcResult(null, Objects.requireNonNull(error));
    }
}
