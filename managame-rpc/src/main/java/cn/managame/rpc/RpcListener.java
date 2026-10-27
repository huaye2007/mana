package cn.managame.rpc;

/**
 * Learns when peers come and go, in either direction. Calls for one peer never overlap and come in order. All run
 * on the EventLoop of the connection that changed and must not block; an exception thrown here is logged.
 * Implement only what is needed.
 */
public interface RpcListener {
    /**
     * A peer's first connection completed its handshake. {@code restarted} tells a new instance of the peer, whose
     * state is gone, from the same instance coming back after its connections dropped: it is true when the peer's
     * {@link RpcPeer#incarnation() incarnation} differs from the one seen before.
     */
    default void onConnected(RpcPeer peer, boolean restarted) {}

    /** A peer's last connection closed, also when the peer was removed or a new instance of it replaced the old. */
    default void onDisconnected(RpcPeer peer) {}

    /**
     * The peer said it is closing: it takes no new calls or notifies, which fail at once, and finishes those it has.
     * Pick another node for new work.
     */
    default void onClosing(RpcPeer peer) {}
}
