package cn.managame.rpc.node;

import io.netty.util.collection.IntObjectHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Copy-on-write Node ID → Peer table. Sends read it without locking or boxing; topology changes are rare
 * and every write runs under the RpcNode lifecycle lock, which keeps the read-modify-publish sequence atomic.
 * A published map is never modified again.
 */
final class PeerTable {
    private volatile IntObjectHashMap<RpcPeer> peers = new IntObjectHashMap<>();
    /** Peers learned from an inbound handshake; bounded by the Node's maxInboundPeers. */
    private volatile int inbound;

    RpcPeer get(int nodeId) {
        return peers.get(nodeId);
    }

    boolean containsKey(int nodeId) {
        return peers.containsKey(nodeId);
    }

    boolean isEmpty() {
        return peers.isEmpty();
    }

    int size() {
        return peers.size();
    }

    int inboundCount() {
        return inbound;
    }

    /** Snapshot of the current Peers. */
    Collection<RpcPeer> values() {
        return List.copyOf(peers.values());
    }

    /** Snapshot of the attached Node IDs; boxed, so it serves diagnostics and tests, never the send path. */
    Set<Integer> nodeIds() {
        return Set.copyOf(peers.keySet());
    }

    // ---- writes: caller holds the lifecycle lock

    void put(int nodeId, RpcPeer peer) {
        IntObjectHashMap<RpcPeer> next = copy(1);
        RpcPeer replaced = next.put(nodeId, peer);
        peers = next;
        inbound += (peer.outbound ? 0 : 1) - (replaced == null || replaced.outbound ? 0 : 1);
    }

    RpcPeer remove(int nodeId) {
        if (!peers.containsKey(nodeId)) return null;
        IntObjectHashMap<RpcPeer> next = copy(0);
        RpcPeer removed = next.remove(nodeId);
        peers = next;
        if (removed != null && !removed.outbound) inbound--;
        return removed;
    }

    /** Removes the entry only while it is still {@code expected}. */
    boolean remove(int nodeId, RpcPeer expected) {
        if (peers.get(nodeId) != expected) return false;
        remove(nodeId);
        return true;
    }

    /** Empties the table and returns what it held. */
    List<RpcPeer> clear() {
        List<RpcPeer> removed = new ArrayList<>(peers.values());
        peers = new IntObjectHashMap<>();
        inbound = 0;
        return removed;
    }

    private IntObjectHashMap<RpcPeer> copy(int extra) {
        IntObjectHashMap<RpcPeer> current = peers;
        IntObjectHashMap<RpcPeer> next = new IntObjectHashMap<>(Math.max(8, current.size() + extra));
        next.putAll(current);
        return next;
    }
}
