package cn.managame.rpc;

import cn.managame.Metadata;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request or notice from a peer, as the {@link RpcHandler} receives it. A request is answered at most once, from
 * any thread, also after the handler returned; a notice expects no answer, and reply and fail then return false.
 */
public final class RpcRequest {
    private final RpcNode node;
    private final RpcLink link;
    private final int requestId;
    private final Object message;
    private final Metadata metadata;
    private final AtomicBoolean answered = new AtomicBoolean();

    RpcRequest(RpcNode node, RpcLink link, int requestId, Object message, Metadata metadata) {
        this.node = node;
        this.link = link;
        this.requestId = requestId;
        this.message = message;
        this.metadata = metadata;
    }

    /** The node that sent it; requests to it go back over the same peer. */
    public RpcPeer peer() { return link.peer; }

    /** The message the codec decoded. */
    public Object message() { return message; }

    /** What the sender attached to it. */
    public Metadata metadata() { return metadata; }

    /** False for a notice. */
    public boolean expectsAnswer() { return requestId != 0; }

    /**
     * Answers with {@code result}; false when it is a notice or was already answered. A result the codec cannot
     * encode fails the request with RPC_HANDLER_ERROR instead, and is logged.
     */
    public boolean reply(Object result) {
        if (!claim()) return false;
        node.reply(link, requestId, result);
        return true;
    }

    /** Answers with a failure; false when it is a notice or was already answered. */
    public boolean fail(int errorCode) {
        if (!claim()) return false;
        node.fail(link, requestId, errorCode);
        return true;
    }

    private boolean claim() { return requestId != 0 && answered.compareAndSet(false, true); }

    @Override public String toString() {
        return (requestId == 0 ? "RpcNotice[" : "RpcRequest[") + message.getClass().getName() + " from " + link.peer + "]";
    }
}
