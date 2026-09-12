package cn.managame.rpc;

import java.util.Objects;

/** Request/notification data only; contains no node, connection or call/reply state. */
public record RpcRequest(
        int command,
        int requestId,
        long routeKey,
        long businessId,
        byte businessIdType,
        RpcMetadata metadata,
        io.netty.buffer.ByteBuf body)
        implements RpcMessage {
    public RpcRequest {
        metadata = RpcMetadataUtil.snapshot(Objects.requireNonNull(metadata));
        Objects.requireNonNull(body, "body");
    }

    public RpcRequest(
            int command, int requestId, RpcOptions options, io.netty.buffer.ByteBuf body) {
        this(
                command,
                requestId,
                options.routeKey(),
                options.busId(),
                options.busType(),
                options.metadata(),
                body);
    }

    public int metadataLength() {
        return metadata.encodedLength();
    }
}
