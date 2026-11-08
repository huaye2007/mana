package cn.managame.rpc;

/**
 * Learns when peers come and go, in either direction. Calls for one peer never overlap and come in order. They run
 * on the EventLoop of the connection that changed and must not block; they may call, notify and answer any peer.
 * An exception thrown here is logged. Implement only what is needed.
 */
public interface RpcListener {
    /**
     * Every connection of a running instance of the peer completed its handshake. An application that keeps state
     * about its peers keeps the peer's {@link RpcPeer#incarnation() incarnation} with it: when that differs here, a
     * new instance came and the state is gone; when it is the same, the instance only came back after its
     * connections dropped.
     */
    default void onConnected(RpcPeer peer) {}

    /**
     * The last connection of the peer closed, also when the peer was removed or a new instance of it took over.
     * Only after {@link #onConnected}: a peer that loses some of its connections stays connected.
     */
    default void onDisconnected(RpcPeer peer) {}
}
