package cn.managame.rpc;

import java.util.Objects;

/** Node connection control; never delivered to the user handler. */
public record RpcHandshake(
        Kind kind, int sourceNodeId, int targetNodeId, int slotIndex, int connectionCount)
        implements RpcMessage {
    public enum Kind {
        HELLO,
        ACK,
        REJECT_DIRECTION
    }

    public RpcHandshake {
        Objects.requireNonNull(kind, "kind");
        if (connectionCount <= 0 || slotIndex < 0 || slotIndex >= connectionCount)
            throw new RpcProtocolException("Invalid handshake slot/count");
    }
}
