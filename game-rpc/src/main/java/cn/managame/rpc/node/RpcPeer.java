package cn.managame.rpc.node;

import cn.managame.network.connection.Connection;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One remote Node. An outbound Peer (this side called addPeer) owns a dial target and recovers its Slots;
 * an inbound Peer was learned from a handshake and never dials. A removed outbound Peer is marked detached
 * so its recovery chains stop.
 */
final class RpcPeer {
    final int nodeId;
    final ConnectionSlot[] slots;
    /** Dial address of an outbound Peer; null for an inbound Peer. */
    final SocketAddress target;
    final boolean outbound;
    volatile boolean detached;
    final ConcurrentHashMap<Integer, PendingCall> pending = new ConcurrentHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicInteger roundRobin = new AtomicInteger();

    RpcPeer(int nodeId, int slotCount, SocketAddress target) {
        this.nodeId = nodeId;
        this.target = target;
        outbound = target != null;
        slots = new ConnectionSlot[slotCount];
        for (int i = 0; i < slotCount; i++) slots[i] = new ConnectionSlot(i);
    }

    /** True while this side should keep dialing empty Slots. */
    boolean recovering() {
        return outbound && !detached;
    }

    int startSlot(long routeKey) {
        return routeKey == 0
                ? Integer.remainderUnsigned(roundRobin.getAndIncrement(), slots.length)
                : (int) Long.remainderUnsigned(routeKey, slots.length);
    }

    /** At least one bound connection is active. */
    boolean connected() {
        for (ConnectionSlot slot : slots) {
            Connection connection = slot.connection.get();
            if (connection != null && connection.isActive()) return true;
        }
        return false;
    }

    /** At least one Slot holds a connection, active or not. */
    boolean bound() {
        for (ConnectionSlot slot : slots) if (slot.connection.get() != null) return true;
        return false;
    }

    /** At least one bound connection is active and writable. */
    boolean hasCandidate() {
        for (ConnectionSlot slot : slots) {
            Connection connection = slot.connection.get();
            if (connection != null && connection.isActive() && connection.isWritable()) return true;
        }
        return false;
    }

    /** Reserves a pending-call place; false when {@code limit} calls are already outstanding. */
    boolean reservePending(int limit) {
        for (;;) {
            int current = pendingCount.get();
            if (current >= limit) return false;
            if (pendingCount.compareAndSet(current, current + 1)) return true;
        }
    }

    /** Returns a reservation that was never registered. */
    void releasePending() {
        pendingCount.decrementAndGet();
    }

    /** Registers a reserved call; false (reservation kept) when the ID is still outstanding. */
    boolean addPending(PendingCall call) {
        return pending.putIfAbsent(call.id, call) == null;
    }

    /** Claims the call with this ID, releasing its reservation; null when absent. */
    PendingCall removePending(int id) {
        PendingCall call = pending.remove(id);
        if (call != null) pendingCount.decrementAndGet();
        return call;
    }

    /** Claims exactly this call, releasing its reservation; false when it already completed. */
    boolean removePending(PendingCall call) {
        if (!pending.remove(call.id, call)) return false;
        pendingCount.decrementAndGet();
        return true;
    }
}
