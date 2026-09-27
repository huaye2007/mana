package cn.managame.rpc.message;

/** Wire handshake data; this is not a peer or a mutable connection context. */
public record RpcHandshake(int nodeId, int slotId, int slotCount) {
    public RpcHandshake {
        if (nodeId == 0) throw new IllegalArgumentException("nodeId must be nonzero");
        if (slotCount < 1 || slotCount > 255 || slotId < 0 || slotId >= slotCount)
            throw new IllegalArgumentException("invalid slot");
    }
}

