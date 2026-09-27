package cn.managame.rpc.message;

import cn.managame.core.Metadata;
import cn.managame.core.Metadatas;
import io.netty.buffer.ByteBuf;

/** Read-only response envelope. Inbound body is borrowed for the duration of RpcHandler.onResponse. */
public record RpcResponse(int requestId, int errorCode, Metadata metadata, ByteBuf body) {
    public RpcResponse {
        if (requestId == 0) throw new IllegalArgumentException("response requestId must be nonzero");
        if (errorCode < 0) throw new IllegalArgumentException("errorCode must be 0..2147483647");
        metadata = metadata == null ? Metadatas.empty() : metadata;
    }
}

