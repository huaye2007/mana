package cn.managame.router.call;

import cn.managame.rpc.message.RpcRequest;
import java.util.Objects;

/**
 * Borrowed request plus its exact source attachment. originRouterId is the Router the caller is attached
 * to; replies return there directly. Save these identity fields for deferred replies.
 */
public record RoutedRequest(int sourceNodeId, long sourceNodeEpoch, RpcRequest request, int originRouterId) {
    public RoutedRequest {
        if (sourceNodeId == 0 || sourceNodeEpoch == 0) throw new IllegalArgumentException("invalid source attachment");
        Objects.requireNonNull(request);
    }
    /** Without an origin Router the reply is located through the replying Router's routing index. */
    public RoutedRequest(int sourceNodeId, long sourceNodeEpoch, RpcRequest request) {
        this(sourceNodeId, sourceNodeEpoch, request, 0);
    }
}
