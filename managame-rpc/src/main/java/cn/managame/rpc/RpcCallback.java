package cn.managame.rpc;

/**
 * Receives the outcome of one call, exactly once, together with the request it answers, so that the application
 * can decode the answer by the request's command and build its context from the request's route key, business and
 * metadata without capturing them, then hand the work to the thread it picks:
 *
 * <pre>{@code
 * peer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> {
 *     Object rank = response.isSuccess() ? protocol.decode(request.command(), response.body()) : null;
 *     executorOf(request.routeKey()).execute(() -> showRank(request.routeKey(), rank, response.code()));
 * });
 * }</pre>
 *
 * <p>It runs on the EventLoop of the connection the answer came on; on the node's time wheel thread when the call
 * timed out; on a dialing EventLoop of the node, picked by the route key, when the call failed before it was sent;
 * on the EventLoop of the peer's last connection when that closed; on the EventLoop of the connection that brought
 * a new instance of the peer, which cannot answer what the old one was asked; on the thread that removed the peer
 * or closed the node. Never on the calling thread inside
 * {@link RpcPeer#call}. Either way it must not block. An exception it throws is logged.
 */
@FunctionalInterface
public interface RpcCallback {
    /**
     * @param request  the request as it was sent, with the id and deadline the call assigned, but with an empty
     *                 body: the body was released once sent
     * @param response the answer; it is released when this method returns, so decode it here or retain it
     */
    void onResponse(RpcRequest request, RpcResponse response);
}
