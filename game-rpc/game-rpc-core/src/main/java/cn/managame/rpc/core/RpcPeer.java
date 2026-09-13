package cn.managame.rpc.core;

import cn.managame.network.Connection;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Logical identity and pending-call owner, independent of physical connection lifetime. */
public final class RpcPeer {
    private final int nodeId, maxPending;
    private final AtomicReferenceArray<Connection> connections;
    private final ConcurrentHashMap<Integer, RpcFuture> pendingCalls = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobin = new AtomicInteger();
    private volatile boolean closed;
    // Set once after any Slot completes its handshake; ordinary disconnects do not clear it.
    private volatile boolean established;

    RpcPeer(int nodeId, int count, int maxPending) {
        if (count < 1) throw new IllegalArgumentException("connectionCount");
        this.nodeId = nodeId;
        this.maxPending = maxPending;
        connections = new AtomicReferenceArray<>(count);
    }

    public int nodeId() {
        return nodeId;
    }

    public int connectionCount() {
        return connections.length();
    }

    public Connection connection(int slot) {
        return closed ? null : connections.get(slot);
    }

    public int pendingCount() {
        return pendingCalls.size();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isReady() {
        if (closed) return false;
        for (int i = 0; i < connectionCount(); i++) {
            var c = connection(i);
            if (c == null || !c.isActive()) return false;
        }
        return true;
    }

    public int nextRoundRobin() {
        return Math.floorMod(roundRobin.getAndIncrement(), connectionCount());
    }

    boolean isEstablished() {
        return established;
    }

    // Published slot changes are serialized by RpcConnections; readers never take its lock.
    void connectionReady(int slot, Connection connection, boolean publishSlot) {
        if (closed) return;
        established = true;
        if (!publishSlot) return; // The accepted loopback end must not replace the outgoing slot.
        connections.set(slot, connection);
    }

    void disconnected(int slot, Connection connection) {
        connections.compareAndSet(slot, connection, null);
    }

    boolean contains(Connection connection) {
        for (int i = 0; i < connectionCount(); i++) if (connection(i) == connection) return true;
        return false;
    }

    /**
     * Atomically validates and registers. Null means an occupied ID; the owner supplies another.
     */
    synchronized RpcFuture tryRegister(int requestId, long deadline, RpcCallback callback) {
        if (closed || !established) throw RpcException.UNAVAILABLE;
        if (pendingCalls.size() >= maxPending) throw RpcException.OVERLOADED;
        if (requestId <= 0) throw new IllegalArgumentException("requestId must be positive");
        if (pendingCalls.containsKey(requestId)) return null;
        var pending = new RpcFuture(requestId, deadline, callback);
        pendingCalls.put(requestId, pending);
        return pending;
    }

    RpcFuture pending(int requestId) {
        return pendingCalls.get(requestId);
    }

    void remove(RpcFuture pending) {
        pendingCalls.remove(pending.requestId, pending);
    }

    List<RpcFuture> pendingSnapshot() {
        return new ArrayList<>(pendingCalls.values());
    }

    synchronized void retire() {
        if (closed) return;
        closed = true;
        for (int i = 0; i < connectionCount(); i++) {
            connections.set(i, null);
        }
    }
}
