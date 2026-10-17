package cn.managame.rpc.node;

/**
 * Approximate readings for one {@link RpcNode}. The first four are current values walked from the Peer table
 * when the snapshot is taken; the rest only rise while the Node runs, so a reader derives a rate by
 * differencing two readings. Nothing here is a barrier: a snapshot is taken without a lock while Peers,
 * connections and pending calls change.
 *
 * <p>Nothing is counted on the success path of a send, a call or a delivery. Frames sent, calls started and
 * requests received are deliberately absent: a caller already knows it called and already receives every
 * request its handler is given, so counting those here would add work per message to report a number the
 * caller can produce for free. What remains is what only the Node sees — what it refused, what timed out,
 * and the state of its own Peers.
 *
 * @param peers Peers configured with addPeer or learned from an inbound handshake
 * @param peersConnected Peers with at least one connected Slot — the reading that says a neighbour is gone
 * @param connections connected Slots across every Peer
 * @param pendingCalls calls registered and not yet completed, across every Peer. Against the configured
 *        maxPendingCalls this is how close a Node is to refusing calls
 * @param callsRejected calls refused before encoding because their Peer was already at maxPendingCalls
 * @param callsTimedOut calls completed by their timeout rather than a response
 * @param sendsRefused sends no Slot accepted: no connected Slot, or every one over its write-buffer high
 *        watermark — the reading that explains messages disappearing under load
 * @param handshakesAccepted connections that completed a handshake and were bound to a Slot
 * @param reconnects recovery attempts scheduled for an outbound Slot after a failure or a disconnect
 */
public record RpcStats(long peers, long peersConnected, long connections, long pendingCalls,
                       long callsRejected, long callsTimedOut, long sendsRefused,
                       long handshakesAccepted, long reconnects) {}
