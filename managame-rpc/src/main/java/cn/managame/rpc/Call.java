package cn.managame.rpc;

import static cn.managame.FrameworkErrorCodes.RPC_TIMEOUT;

import io.netty.util.ReferenceCounted;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import java.lang.System.Logger.Level;

/**
 * One call: the message a connection encodes as a Request, under the id and deadline the call assigned, and the
 * entry its peer's {@link PendingCalls} keep until it ends. Its timeout on the node's time wheel fails it with
 * RPC_TIMEOUT. Reference-counted through the request's body, so that Netty releases the body of a call it could
 * not write.
 */
final class Call implements ReferenceCounted, TimerTask {
    private static final System.Logger LOG = System.getLogger("cn.managame.rpc");
    final PendingCalls calls;
    final RpcRequest request;
    final RpcCallback callback;
    /** A {@link System#nanoTime()} value, never 0. */
    final long deadline;
    /** Assigned by {@link PendingCalls#add}, before the call joins them, which publishes it. */
    int id;
    /** Set once the call waits in its table, and cancelled when it ends otherwise. */
    Timeout timeout;

    Call(PendingCalls calls, RpcRequest request, RpcCallback callback, long deadline) {
        this.calls = calls;
        this.request = request;
        this.callback = callback;
        this.deadline = deadline != 0 ? deadline : 1;
    }

    /** The time wheel: no answer in time. */
    @Override public void run(Timeout timeout) {
        if (calls.remove(this)) fail(RPC_TIMEOUT);
    }

    /** Ends the call with {@code response}, which the caller releases afterwards. */
    void complete(RpcResponse response) {
        Timeout pending = timeout;
        if (pending != null) pending.cancel();
        try {
            callback.onResponse(request.sentAs(id, deadline), response);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "An RPC callback threw", e);
        }
    }

    void fail(int code) { complete(RpcResponse.failure(id, code)); }

    @Override public int refCnt() { return request.refCnt(); }
    @Override public Call retain() { request.retain(); return this; }
    @Override public Call retain(int increment) { request.retain(increment); return this; }
    @Override public Call touch() { request.touch(); return this; }
    @Override public Call touch(Object hint) { request.touch(hint); return this; }
    @Override public boolean release() { return request.release(); }
    @Override public boolean release(int decrement) { return request.release(decrement); }

    @Override public String toString() { return "Call[" + id + ", command " + request.command() + "]"; }
}
