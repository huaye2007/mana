package cn.managame.rpc.node;

import java.util.concurrent.atomic.LongAdder;

/**
 * The monotonic counters behind {@link RpcStats}. LongAdder throughout, because every one of these is
 * incremented from EventLoops, the caller's thread or the timer thread at once. The current values — Peers,
 * connections and pending calls — are not counted here; {@link RpcNode#stats()} walks the Peer table for
 * them, so nothing has to be kept in step with it.
 */
final class RpcCounters {
    private final LongAdder callsStarted = new LongAdder(), callsRejected = new LongAdder(),
            callsTimedOut = new LongAdder(), framesSent = new LongAdder(), sendsRefused = new LongAdder(),
            requestsReceived = new LongAdder(), handshakesAccepted = new LongAdder(), reconnects = new LongAdder();

    void callStarted() { callsStarted.increment(); }
    void callRejected() { callsRejected.increment(); }
    void callTimedOut() { callsTimedOut.increment(); }
    void frameSent() { framesSent.increment(); }
    void sendRefused() { sendsRefused.increment(); }
    void requestReceived() { requestsReceived.increment(); }
    void handshakeAccepted() { handshakesAccepted.increment(); }
    void reconnect() { reconnects.increment(); }

    RpcStats snapshot(long peers, long peersConnected, long connections, long pendingCalls) {
        return new RpcStats(peers, peersConnected, connections, pendingCalls,
                callsStarted.sum(), callsRejected.sum(), callsTimedOut.sum(), framesSent.sum(),
                sendsRefused.sum(), requestsReceived.sum(), handshakesAccepted.sum(), reconnects.sum());
    }
}
