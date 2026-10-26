package cn.managame.rpc;

import cn.managame.Metadata;
import cn.managame.runtime.Context;
import java.util.Objects;

/**
 * Where a request from another node came from: the calling peer and the context of the task that sent it. A
 * handler finds it as {@code HandleContext.source()}; the request runs on its own handler's route, acting for
 * this business identity with this metadata. The handler may call the peer back through {@link #peer}.
 */
public record RpcSource(RpcPeer peer, int domain, long routingKey, byte businessType, long businessId, Metadata metadata)
        implements Context {
    public RpcSource {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(metadata, "metadata");
    }

    /** The calling node. */
    public int nodeId() { return peer.nodeId(); }
}
