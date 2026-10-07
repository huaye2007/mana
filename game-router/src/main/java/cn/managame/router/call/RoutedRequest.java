package cn.managame.router.call;

import cn.managame.rpc.message.RpcRequest;
import java.util.Objects;

/** Borrowed request plus its exact source attachment. Save identity fields for deferred replies. */
public record RoutedRequest(int sourceNodeId, long sourceNodeEpoch, RpcRequest request) {
    public RoutedRequest {
        if (sourceNodeId == 0 || sourceNodeEpoch == 0) throw new IllegalArgumentException("invalid source attachment");
        Objects.requireNonNull(request);
    }
}
