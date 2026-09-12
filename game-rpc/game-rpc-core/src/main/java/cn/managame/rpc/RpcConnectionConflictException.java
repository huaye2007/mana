package cn.managame.rpc;

/** Both nodes have declared themselves the connection initiator for the same node pair. */
public final class RpcConnectionConflictException extends IllegalStateException {
    public RpcConnectionConflictException(int localNodeId, int peerNodeId) {
        super(
                "Only one node may initiate connections: local="
                        + localNodeId
                        + ", peer="
                        + peerNodeId);
    }
}
