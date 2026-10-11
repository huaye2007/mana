package cn.managame.rpc.node;

import io.netty.util.Timeout;
import java.util.concurrent.atomic.AtomicBoolean;

final class RpcConnectionContext {
    volatile RpcPeer expectedPeer, peer;
    ConnectionSlot expectedSlot, slot;
    volatile Timeout timeout;
    final AtomicBoolean handshakeFinished = new AtomicBoolean();
    /** Nonce sent in our initiating handshake; the reply must echo it. */
    volatile byte[] sentNonce;
    boolean disconnected;
    void cancelHandshake() {
        handshakeFinished.set(true);
        Timeout current = timeout;
        if (current != null) current.cancel();
    }
}


