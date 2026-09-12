package cn.managame.rpc;

import cn.managame.network.*;

import io.netty.buffer.ByteBuf;
import io.netty.util.Timer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Owns call registration, deadlines and completion; never retains a request body or RpcNode. */
final class RpcCalls {
    final AtomicInteger requestIdCounter = new AtomicInteger();
    private final Timer timer;
    private final RpcTransport transport;
    private final Duration defaultTimeout;
    private final BooleanSupplier running;
    private final RpcObserver observer;

    RpcCalls(
            Timer timer,
            RpcTransport transport,
            Duration defaultTimeout,
            BooleanSupplier running,
            RpcObserver observer) {
        this.timer = timer;
        this.transport = transport;
        this.defaultTimeout = defaultTimeout;
        this.running = running;
        this.observer = observer;
    }

    long deadline(Duration timeout) {
        long duration = RpcChecks.timeoutNanos(timeout == null ? defaultTimeout : timeout);
        long deadline;
        try {
            deadline = Math.addExact(System.nanoTime(), duration);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Deadline overflow", e);
        }
        return deadline;
    }

    /** Registration owns timer setup and rollback, so a caller never sees a half-started Call. */
    RpcFuture begin(RpcPeer peer, long deadline, RpcCallback callback) {
        var pending = register(peer, deadline, callback);
        try {
            schedule(peer, pending);
        } catch (RuntimeException failure) {
            observer.observe("call-submit-failed", peer.nodeId(), pending.requestId, null, failure);
            fail(peer, pending, localError(failure));
        }
        return pending;
    }

    private void schedule(RpcPeer peer, RpcFuture pending) {
        pending.timer(
                timer.newTimeout(
                        ignored -> timeoutCall(peer, pending),
                        Math.max(0, pending.deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS));
    }

    void fail(RpcPeer peer, RpcFuture pending, RpcError error) {
        finishCall(peer, pending, RpcResult.failure(error), false);
    }

    void protocolFailure(RpcPeer peer, int requestId) {
        var pending = peer.pending(requestId);
        if (pending != null)
            finishCall(peer, pending, RpcResult.failure(RpcError.PROTOCOL_ERROR), true);
    }

    void reject(int target, RpcCallback callback, RpcError error) {
        observeCompletion(target, null, false, error);
        notifyResult(callback, RpcResult.failure(error));
    }

    private RpcFuture register(RpcPeer peer, long deadline, RpcCallback callback) {
        // Avoid allocating IDs for peers that have never completed a handshake.
        if (!running.getAsBoolean() || peer.isClosed() || !peer.isEstablished())
            throw RpcException.UNAVAILABLE;
        for (; ; ) {
            int id = requestIdCounter.updateAndGet(v -> v == Integer.MAX_VALUE ? 1 : v + 1);
            var pending = peer.tryRegister(id, deadline, callback);
            if (pending != null) return pending;
            if (!running.getAsBoolean()) throw RpcException.UNAVAILABLE;
        }
    }

    private boolean claimCall(RpcPeer peer, RpcFuture pending) {
        if (!pending.tryComplete()) return false;
        peer.remove(pending);
        return true;
    }

    /** Claim and detach before any callback; response deadlines are checked under the same lock. */
    private RpcResult complete(
            RpcPeer peer, RpcFuture pending, RpcResult result, boolean response) {
        synchronized (pending) {
            if (pending.isDone()) return null;
            if (response && System.nanoTime() - pending.deadline >= 0)
                result = RpcResult.failure(RpcError.TIMEOUT);
            return claimCall(peer, pending) ? result : null;
        }
    }

    private void finishCall(RpcPeer peer, RpcFuture pending, RpcResult result, boolean response) {
        var completed = complete(peer, pending, result, response);
        if (completed != null) notifyCall(peer, pending, completed);
    }

    void timeoutCall(RpcPeer peer, RpcFuture pending) {
        var result = complete(peer, pending, RpcResult.failure(RpcError.TIMEOUT), false);
        if (result != null) notifyCall(peer, pending, result);
    }

    boolean expireCall(RpcPeer peer, RpcFuture pending) {
        if (System.nanoTime() - pending.deadline < 0) return false;
        timeoutCall(peer, pending);
        return true;
    }

    private void notifyCall(RpcPeer peer, RpcFuture pending, RpcResult result) {
        observeCompletion(peer.nodeId(), pending.requestId, result.isSuccess(), result.error());
        notifyResult(pending.callback, result);
    }

    List<RpcFuture> settlePeer(RpcPeer peer) {
        var pending = peer.pendingSnapshot();
        pending.removeIf(call -> !claimCall(peer, call));
        return pending;
    }

    void notifyClosed(Map<RpcPeer, List<RpcFuture>> closing) {
        var result = RpcResult.failure(RpcError.UNAVAILABLE);
        closing.forEach((peer, pending) -> pending.forEach(call -> notifyCall(peer, call, result)));
    }

    void receiveResponse(RpcPeer peer, RpcResponse response) {
        var pending = peer.pending(response.requestId());
        if (pending == null || pending.isDone()) {
            observer.observe(
                    "late-or-unknown-response", peer.nodeId(), response.requestId(), null, null);
            return;
        }
        if (expireCall(peer, pending)) return;
        finishCall(peer, pending, RpcResult.received(response), true);
    }

    RpcTransport.Submission submit(
            RpcPeer peer, RpcFuture pending, Connection connection, ByteBuf frame) {
        RpcError error = null;
        synchronized (pending) {
            if (pending.isDone()) return RpcTransport.Submission.UNAVAILABLE;
            if (peer.isClosed() || !running.getAsBoolean()) error = RpcError.UNAVAILABLE;
            else if (System.nanoTime() - pending.deadline >= 0) error = RpcError.TIMEOUT;
            // Passing this check commits submission. Later timeout/removal may finish the Call,
            // but cannot retract an in-flight write. Never invoke a Transport under this monitor:
            // it may synchronously deliver requests, responses, diagnostics or disconnections.
        }
        if (error != null) {
            fail(peer, pending, error);
            return RpcTransport.Submission.UNAVAILABLE;
        }
        var result = transport.write(connection, frame);
        if (result != RpcTransport.Submission.ACCEPTED)
            fail(
                    peer,
                    pending,
                    result == RpcTransport.Submission.OVERLOADED
                            ? RpcError.OVERLOADED
                            : RpcError.UNAVAILABLE);
        return result;
    }

    static RpcError localError(Exception e) {
        if (e instanceof RpcException rpc) return rpc.error();
        return e instanceof RpcProtocolException
                ? RpcError.PROTOCOL_ERROR
                : RpcError.INTERNAL_ERROR;
    }

    private void notifyResult(RpcCallback callback, RpcResult result) {
        try {
            callback.onResult(result);
        } catch (Throwable e) {
            observer.observe("callback-failed", e);
        }
    }

    private void observeCompletion(int peer, Integer requestId, boolean success, RpcError error) {
        observer.observe(
                success ? "call-success" : "call-failure",
                peer,
                requestId,
                null,
                error == null ? null : RpcException.signal(error));
    }
}
