package cn.managame.rpc.call;

import cn.managame.rpc.message.RpcRequest;
import cn.managame.rpc.message.RpcResponse;

/**
 * Must be thread-safe and return quickly. The application owns body decoding, error interpretation and
 * business dispatch.
 *
 * <p>Threads: requests, responses and failures detected on a connection (a malformed response, loss of a
 * Peer's last connection) run on Netty EventLoops; immediate send failures run on the calling thread;
 * removal and closure failures on the management thread; call timeouts on the Node's timer thread. The
 * framework only notifies. Business handling belongs on the application's own executor: an implementation
 * of {@link #onFail} (and of the other callbacks, when their work is not trivial) should submit the work
 * there, for example to the caller's Runtime Route, because a blocked timer thread delays later timeouts,
 * handshake timeouts and reconnects, and a blocked EventLoop delays every connection on it.
 */
public interface RpcHandler {
    /** Body is borrowed. Retain/copy before saving it or transferring it to reply(). */
    void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request);
    /** Pending call is removed and timeout cancelled before entry. Body is borrowed. */
    void onResponse(int sourceNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    /**
     * A local outbound call failure; remote error responses go to onResponse instead. TIMEOUT arrives on
     * the timer thread: hand the business reaction to the application's executor.
     */
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}
