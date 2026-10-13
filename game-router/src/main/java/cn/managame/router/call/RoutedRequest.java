package cn.managame.router.call;

import cn.managame.rpc.message.RpcRequest;
import java.util.Objects;

/**
 * A borrowed routed request with its exact source: the sending Node, its incarnation, and the Router that
 * delivered it. A reply returns through that Router to that incarnation; keep these fields for a later reply.
 */
public record RoutedRequest(int sourceNodeId, long sourceNodeEpoch, RpcRequest request, int routerId) {
    public RoutedRequest {
        if (sourceNodeId == 0 || sourceNodeEpoch == 0 || routerId == 0) throw new IllegalArgumentException("invalid routed source");
        Objects.requireNonNull(request);
    }
}
