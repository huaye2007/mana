package cn.managame.rpc;

import io.netty.buffer.ByteBuf;

import java.util.Arrays;
import java.util.Objects;

/** A response carries either a successful raw body or error arguments owned by the message. */
public record RpcResponse(
        int requestId, int errorCode, RpcMetadata metadata, ByteBuf body, String[] errorArgs)
        implements RpcMessage {
    private static final String[] NO_ARGS = new String[0];

    public RpcResponse {
        if (errorCode == 0) Objects.requireNonNull(body, "body");
        metadata = RpcMetadataUtil.snapshot(Objects.requireNonNull(metadata, "metadata"));
        Objects.requireNonNull(errorArgs, "errorArgs");
        errorArgs = errorArgs.length == 0 ? NO_ARGS : errorArgs.clone();
        for (var argument : errorArgs) Objects.requireNonNull(argument, "error argument");
    }

    /** Existing successful responses and errors without arguments. */
    public RpcResponse(int requestId, int errorCode, RpcMetadata metadata, ByteBuf body) {
        this(requestId, errorCode, metadata, body, NO_ARGS);
    }

    public static RpcResponse error(int requestId, int errorCode, String... errorArgs) {
        return error(requestId, errorCode, RpcMetadata.EMPTY, errorArgs);
    }

    public static RpcResponse error(
            int requestId, int errorCode, RpcMetadata metadata, String... errorArgs) {
        if (errorCode <= 0) throw new IllegalArgumentException("errorCode must be positive");
        return new RpcResponse(requestId, errorCode, metadata, null, errorArgs);
    }

    public static RpcResponse error(int requestId, RpcError error, String... errorArgs) {
        return error(requestId, error, RpcMetadata.EMPTY, errorArgs);
    }

    public static RpcResponse error(
            int requestId, RpcError error, RpcMetadata metadata, String... errorArgs) {
        Objects.requireNonNull(error, "error");
        return error(requestId, error.code(), metadata, errorArgs);
    }

    @Override
    public String[] errorArgs() {
        return errorArgs.length == 0 ? NO_ARGS : errorArgs.clone();
    }

    // Internal operations use the private immutable snapshot without exporting its array.
    int errorArgsLength(int maximum) {
        return RpcErrorArgs.encodedLength(errorArgs, maximum);
    }

    boolean hasErrorArgs() {
        return errorArgs.length != 0;
    }

    void writeErrorArgs(ByteBuf output) {
        RpcErrorArgs.write(output, errorArgs);
    }

    public int metadataLength() {
        return metadata.encodedLength();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RpcResponse r
                && requestId == r.requestId
                && errorCode == r.errorCode
                && Objects.equals(metadata, r.metadata)
                && Objects.equals(body, r.body)
                && Arrays.equals(errorArgs, r.errorArgs);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(requestId, errorCode, metadata, body) + Arrays.hashCode(errorArgs);
    }
}
