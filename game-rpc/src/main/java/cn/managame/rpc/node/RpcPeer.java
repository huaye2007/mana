package cn.managame.rpc.node;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class RpcPeer {
    final int nodeId;
    final ConnectionSlot[] slots;
    volatile SocketAddress target;
    final ConcurrentHashMap<Integer, PendingCall> pending = new ConcurrentHashMap<>();
    final AtomicInteger requestId = new AtomicInteger(1);
    final AtomicInteger roundRobin = new AtomicInteger();

    RpcPeer(int nodeId, int slotCount, SocketAddress target) {
        this.nodeId = nodeId;
        this.target = target;
        slots = new ConnectionSlot[slotCount];
        for (int i = 0; i < slotCount; i++) slots[i] = new ConnectionSlot(i);
    }
    int nextRequestId() {
        int id;
        do { id = requestId.getAndIncrement(); } while (id == 0);
        return id;
    }
    int startSlot(long routeKey) {
        return routeKey == 0
                ? Integer.remainderUnsigned(roundRobin.getAndIncrement(), slots.length)
                : (int) Long.remainderUnsigned(routeKey, slots.length);
    }
    boolean hasCandidate() {
        for (ConnectionSlot slot : slots) {
            var c = slot.connection.get();
            if (c != null && c.isActive() && c.isWritable()) return true;
        }
        return false;
    }
}

