package cn.managame.rpc.message;

import cn.managame.core.Metadata;
import cn.managame.core.Metadatas;
import io.netty.buffer.ByteBuf;

/**
 * Read-only RPC envelope. A nonzero requestId represents an inbound call.
 * Outbound call/notify require requestId == 0; the node assigns the wire ID.
 * The body is borrowed during inbound callbacks. Retain/copy it before asynchronous use.
 */
public record RpcRequest(int command, int requestId, long routeKey, int businessIdType,
                         long businessId, Metadata metadata, ByteBuf body) {
    public RpcRequest {
        if (command == 0) throw new IllegalArgumentException("command must be nonzero");
        if ((businessIdType & ~255) != 0) throw new IllegalArgumentException("businessIdType must be 0..255");
        metadata = metadata == null ? Metadatas.empty() : metadata;
    }
    public RpcRequest(int command, ByteBuf body) {
        this(command, 0, 0, 0, 0, null, body);
    }
    public RpcRequest(int command, long routeKey, int businessIdType, long businessId,
                      Metadata metadata, ByteBuf body) {
        this(command, 0, routeKey, businessIdType, businessId, metadata, body);
    }
}

