package cn.managame.rpc.node;

import java.util.concurrent.atomic.LongAdder;

/**
 * The monotonic counters behind {@link RpcStats}. Every one of them is incremented either once per
 * connection or on a branch that was already exceptional — a call refused at the per-Peer limit, a send no
 * Slot accepted, a timeout — so a Node sending and receiving normally never touches this class. The steady
 * state cost of these readings is therefore zero rather than small, which is why there is nothing to
 * configure or turn off.
 *
 * <p>LongAdder throughout, because a refusal can arrive from the caller's thread, a timeout from the timer
 * thread and a handshake from an EventLoop. The current values — Peers, connections and pending calls — are
 * not counted here; {@link RpcNode#stats()} walks the Peer table for them, so nothing has to be kept in step
 * with it.
 */
final class RpcCounters {
    private final LongAdder callsRejected = new LongAdder(), callsTimedOut = new LongAdder(),
            sendsRefused = new LongAdder(), handshakesAccepted = new LongAdder(), reconnects = new LongAdder();

    void callRejected() { callsRejected.increment(); }
    void callTimedOut() { callsTimedOut.increment(); }
    void sendRefused() { sendsRefused.increment(); }
    void handshakeAccepted() { handshakesAccepted.increment(); }
    void reconnect() { reconnects.increment(); }

    RpcStats snapshot(long peers, long peersConnected, long connections, long pendingCalls) {
        return new RpcStats(peers, peersConnected, connections, pendingCalls,
                callsRejected.sum(), callsTimedOut.sum(), sendsRefused.sum(),
                handshakesAccepted.sum(), reconnects.sum());
    }
}
