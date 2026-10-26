package cn.managame.rpc;

import cn.managame.runtime.Context;
import java.util.Arrays;
import java.util.Objects;

/**
 * Another process of the cluster, known by its node id, whichever side opened the connections: those this node
 * opens to a configured peer and those the peer opens to this node all carry calls both ways. Obtained from
 * {@link RpcNode#peer}:
 *
 * <pre>{@code
 * node.peer(1).call(new RankOf(), (Context c, Integer rank) -> request.reply(rank), (c, errorCode) -> request.fail(errorCode));
 * }</pre>
 *
 * A call takes the connection its calling route hashes to, so the messages of one route reach the peer in the
 * order they were sent, as long as the peer's connections do not change. A peer with no connection, including
 * one that has not connected yet, fails calls with RPC_UNAVAILABLE.
 */
public final class RpcPeer {
    private static final RpcLink[] NONE = {};
    private final RpcNode node;
    private final int nodeId;
    private volatile RpcLink[] links = NONE;

    RpcPeer(RpcNode node, int nodeId) {
        this.node = node;
        this.nodeId = nodeId;
    }

    public int nodeId() { return nodeId; }

    /** Whether at least one connection to the peer is up, so that calls to it can be sent. */
    public boolean isConnected() { return links.length > 0; }

    /** The connections up now, in both directions. */
    public int connectionCount() { return links.length; }

    /** {@link #call(Context, Object, RpcResult, RpcFailure)} from the running game task's context. */
    public <R> void call(Object request, RpcResult<? super R> onResult, RpcFailure onFailure) {
        call(Context.current(), request, onResult, onFailure);
    }

    /** As above, with failures logged. */
    public <R> void call(Object request, RpcResult<? super R> onResult) {
        call(Context.current(), request, onResult, null);
    }

    /**
     * Sends {@code request} to the peer, acting for {@code context}'s business identity with its metadata. The
     * result, or the failure, runs as a task on {@code context}; a null {@code onFailure} logs failures. A request
     * the codec cannot encode throws what it threw.
     */
    @SuppressWarnings("unchecked")
    public <R> void call(Context context, Object request, RpcResult<? super R> onResult, RpcFailure onFailure) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onResult, "onResult");
        node.call(this, context, request, (RpcResult<Object>) onResult, onFailure);
    }

    /** {@link #notice(Context, Object)} acting for the running game task, if any, otherwise for nobody. */
    public boolean notice(Object message) {
        return notice(Context.hasCurrent() ? Context.current() : RpcNode.NO_CALLER, message);
    }

    /**
     * Sends {@code message} to the peer, acting for {@code context}, expecting no answer. False when it could not
     * be sent, for example because the peer is not connected.
     */
    public boolean notice(Context context, Object message) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(message, "message");
        return node.notice(this, context, message);
    }

    /** The connection for {@code context}'s route, or null when there is none. */
    RpcLink link(Context context) {
        RpcLink[] current = links;
        if (current.length <= 1) return current.length == 0 ? null : current[0];
        int hash = 31 * context.domain() + Long.hashCode(context.routingKey());
        return current[Math.floorMod(hash ^ (hash >>> 16), current.length)];
    }

    RpcLink[] links() { return links; }

    synchronized void add(RpcLink link) {
        RpcLink[] grown = Arrays.copyOf(links, links.length + 1);
        grown[links.length] = link;
        links = grown;
    }

    synchronized void remove(RpcLink link) {
        links = Arrays.stream(links).filter(other -> other != link).toArray(RpcLink[]::new);
    }

    @Override public String toString() { return "RpcPeer[" + nodeId + ", " + links.length + " connections]"; }
}
