package cn.managame.rpc;

import io.netty.util.Timeout;

/** Single-call state. Registration, scheduling and result delivery belong to the owner. */
final class RpcFuture {
    final int requestId;
    final long deadline;
    final RpcCallback callback;
    private Timeout timer;
    private volatile boolean done;

    RpcFuture(int requestId, long deadline, RpcCallback callback) {
        this.requestId = requestId;
        this.deadline = deadline;
        this.callback = callback;
    }

    synchronized void timer(Timeout value) {
        timer = value;
        if (done) value.cancel();
    }

    boolean isDone() {
        return done;
    }

    /** Claims completion once and cancels this call's timer handle, if already installed. */
    synchronized boolean tryComplete() {
        if (done) return false;
        done = true;
        if (timer != null) timer.cancel();
        return true;
    }
}
