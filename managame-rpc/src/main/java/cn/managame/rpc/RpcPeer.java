package cn.managame.rpc;

import cn.managame.Metadata;
import java.util.Arrays;
import java.util.Objects;

/**
 * Another process of the cluster, known by its node id, whichever side opened the connections: those this node
 * opens to a configured peer and those the peer opens to this node all carry calls both ways. Obtained from
 * {@link RpcNode#peer}:
 *
 * <pre>{@code
 * node.peer(1).call(new RankOf(playerId), RpcCallback.of(rank -> ..., errorCode -> ...));
 * }</pre>
 *
 * A call takes the connection its key hashes to, so messages sent with one key reach the peer in the order they
 * were sent, as long as the peer's connections do not change. A peer with no connection, including one that has
 * not connected yet, fails calls with RPC_UNAVAILABLE.
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

    /** {@link #call(Object, Metadata, long, RpcCallback)} with no metadata and key 0. */
    public <R> void call(Object request, RpcCallback<R> callback) { call(request, Metadata.empty(), 0, callback); }

    /**
     * Sends {@code request} with {@code metadata}, on the connection {@code key} picks, and hands the answer to
     * {@code callback}. A request the codec cannot encode throws what it threw; nothing else does.
     */
    @SuppressWarnings("unchecked")
    public <R> void call(Object request, Metadata metadata, long key, RpcCallback<R> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(callback, "callback");
        node.call(this, request, metadata, key, (RpcCallback<Object>) callback);
    }

    /** {@link #notice(Object, Metadata, long)} with no metadata and key 0. */
    public boolean notice(Object message) { return notice(message, Metadata.empty(), 0); }

    /**
     * Sends {@code message} with {@code metadata}, on the connection {@code key} picks, expecting no answer. False
     * when it could not be sent, for example because the peer is not connected.
     */
    public boolean notice(Object message, Metadata metadata, long key) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(metadata, "metadata");
        return node.notice(this, message, metadata, key);
    }

    /** The connection for {@code key}, or null when there is none. */
    RpcLink link(long key) {
        RpcLink[] current = links;
        if (current.length <= 1) return current.length == 0 ? null : current[0];
        int hash = Long.hashCode(key);
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
