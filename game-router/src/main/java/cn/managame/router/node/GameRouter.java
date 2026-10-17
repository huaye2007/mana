package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcErrorCodes;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import static cn.managame.router.node.RouterWire.*;

/**
 * Forwarding Router on the application-owned RpcNode. A service Node connects to one Router and announces
 * itself there; the Routers form a mesh and tell each other which Nodes are attached to them. A message for
 * an exact Node is delivered when that Node is attached here and otherwise passed once to the Router that
 * reported it; a message that arrived from another Router is only ever delivered locally, which makes the
 * mesh loop-free without a hop count. The only state is the announcements of live connections and the
 * locations other Routers reported, both bounded by the number of Nodes.
 *
 * <p>Construct it before the RPC Node, pass it as the Node's handler and associate it through {@link #start};
 * declare the other Routers with {@link #addRouter}. The application owns every RPC Peer, including the mesh
 * connections: the Router with the larger ID adds the smaller one, so each pair has one connection. Router
 * Profile requests are relayed; every other request, and every response (a Router never starts calls), goes
 * to the application's direct handler. Forwarding runs on the Netty thread that received the message; one
 * daemon thread refreshes the location reports.
 */
public final class GameRouter implements RpcHandler, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(GameRouter.class.getName());
    /** Attachments kept at once; past it, entries of closed connections are dropped first. */
    static final int MAX_NODES = 100_000;
    /** How often the complete set of local attachments is reported to each connected Router. */
    static final long REFRESH_MILLIS = 1000;
    private static final long TICK_MILLIS = 100;

    /**
     * Message counts since construction, for monitoring; every broadcast copy counts once.
     *
     * @param forwarded requests, broadcast copies and responses delivered to a Node attached here
     * @param passed messages handed to another Router because the target is attached there
     * @param targetNotFound messages with no usable location (a Call was answered PEER_NOT_FOUND)
     * @param sendRefused messages RPC did not accept for the next hop: connection gone or write buffer full
     * @param responsesDropped responses whose caller was no longer attached with that epoch, or unreachable
     */
    public record Stats(long forwarded, long passed, long targetNotFound, long sendRefused, long responsesDropped) {}

    /** The Node announced on a live connection here. Identity equality, so a replaced entry is never removed by mistake. */
    private static final class Attachment {
        final long epoch; final int service;
        Attachment(long epoch, int service) { this.epoch = epoch; this.service = service; }
    }

    /** A Node another Router reported. Identity equality, for the same reason as {@link Attachment}. */
    private static final class Location {
        final long epoch; final int service; final int router;
        Location(long epoch, int service, int router) { this.epoch = epoch; this.service = service; this.router = router; }
    }

    /** A declared mesh Router. {@code up} belongs to the refresh thread. */
    private static final class Link {
        final int id;
        boolean up;
        Link(int id) { this.id = id; }
    }

    private final RpcHandler direct;
    /** Node ID → identity announced here. Read lock-free on the data path; changed only under {@code writeLock}. */
    private final ConcurrentHashMap<Integer, Attachment> nodes = new ConcurrentHashMap<>();
    /** Service ID → Node IDs attached here, kept consistent with {@code nodes} so broadcasts visit one service. */
    private final ConcurrentHashMap<Integer, Set<Integer>> services = new ConcurrentHashMap<>();
    /** Node ID → where another Router reported it. Read lock-free; changed only under {@code writeLock}. */
    private final ConcurrentHashMap<Integer, Location> remote = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile Link[] routers = {};              // declared mesh Routers, ascending ID; replaced, never mutated
    private final LongAdder forwarded = new LongAdder(), passed = new LongAdder(), targetNotFound = new LongAdder(),
            sendRefused = new LongAdder(), responsesDropped = new LongAdder();
    private volatile RpcNode rpc;
    private volatile boolean closed;
    private ScheduledExecutorService refresher;
    private long lastRefresh;

    public GameRouter(RpcHandler directHandler) { direct = Objects.requireNonNull(directHandler); }

    /** Associates the application-owned Node once and starts reporting locations. It starts no networking. */
    public synchronized void start(RpcNode rpcNode) {
        Objects.requireNonNull(rpcNode);
        if (closed || rpc != null) throw new IllegalStateException("Router already started or closed");
        rpc = rpcNode;
        lastRefresh = System.nanoTime();
        refresher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread t = new Thread(task, "game-router-locations"); t.setDaemon(true); return t;
        });
        refresher.scheduleWithFixedDelay(this::refresh, 0, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }
    /**
     * Declares another Router of the mesh. Only a declared Router may report locations here, and only a
     * declared Router's reports are trusted. The application connects the pair separately with
     * RpcNode.addPeer, from the larger ID to the smaller one.
     */
    public synchronized void addRouter(int routerId) {
        RpcNode node = running();
        if (routerId == 0 || routerId == node.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        if (link(routerId) != null) return;
        Link[] next = Arrays.copyOf(routers, routers.length + 1);
        next[next.length - 1] = new Link(routerId);
        Arrays.sort(next, Comparator.comparingInt(l -> l.id));
        routers = next;
    }
    /** Stops using a Router of the mesh and forgets everything it reported; the RPC Peer is the application's. */
    public synchronized void removeRouter(int routerId) {
        running();
        if (link(routerId) == null) return;
        routers = Arrays.stream(routers).filter(l -> l.id != routerId).toArray(Link[]::new);
        synchronized (writeLock) { forget(routerId); }
    }
    /** Stops relaying and forgets every attachment and reported location. Idempotent; close the RpcNode afterwards. */
    @Override public void close() {
        RpcNode node; ScheduledExecutorService stopping; List<Entry> withdrawn;
        synchronized (this) {
            if (closed) return;
            closed = true; node = rpc; stopping = refresher;
        }
        synchronized (writeLock) { withdrawn = localEntries(); }
        if (stopping != null) {
            stopping.shutdown();
            try { stopping.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        // Tell the mesh these Nodes are gone while the connections are still up; a lost report is harmless.
        if (node != null && !withdrawn.isEmpty())
            for (Link l : routers) if (node.isPeerConnected(l.id)) RouterWire.notify(node, l.id, unlocate(withdrawn));
        clearState();
    }

    public Stats stats() {
        return new Stats(forwarded.sum(), passed.sum(), targetNotFound.sum(), sendRefused.sum(), responsesDropped.sum());
    }
    /** Whether {@code nodeId} has announced itself here on a connection that is still up. */
    public boolean isAttached(int nodeId) {
        RpcNode node = rpc;
        return !closed && node != null && nodes.containsKey(nodeId) && node.isPeerConnected(nodeId);
    }
    /** Whether this Router could deliver to {@code nodeId}, here or through the Router that reported it. */
    public boolean isReachable(int nodeId) {
        if (isAttached(nodeId)) return true;
        RpcNode node = rpc;
        return node != null && located(node, nodeId, 0) != null;
    }
    /** Nodes of the service attached here over live connections; a snapshot, not a delivery guarantee. */
    public Set<Integer> serviceNodes(int serviceId) {
        if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
        Set<Integer> ids = services.get(serviceId);
        if (ids == null) return Set.of();
        Set<Integer> result = new HashSet<>();
        for (int id : ids) if (isAttached(id)) result.add(id);
        return Set.copyOf(result);
    }

    private RpcNode running() {
        RpcNode node = rpc;
        if (node == null) throw new IllegalStateException("Start the Router first");
        if (closed) throw new IllegalStateException("Router closed");
        return node;
    }
    private Link link(int routerId) {
        for (Link l : routers) if (l.id == routerId) return l;
        return null;
    }
    private boolean isRouter(int nodeId) { return link(nodeId) != null; }

    // ---------------------------------------------------------------- location reports (refresh thread)

    /**
     * Reports the complete set of local attachments to each connected Router: at once when its connection is
     * new, so a reconnected mesh link converges without waiting, and every {@link #REFRESH_MILLIS} otherwise.
     * Forgets what a Router reported once its connection is gone.
     */
    private void refresh() {
        try {
            if (closed) return;
            RpcNode node = rpc;
            if (node == null) return;
            long now = System.nanoTime();
            boolean due = now - lastRefresh >= TimeUnit.MILLISECONDS.toNanos(REFRESH_MILLIS);
            if (due) lastRefresh = now;
            List<Entry> local = null;
            for (Link l : routers) {
                if (!node.isPeerConnected(l.id)) {
                    if (l.up) { l.up = false; synchronized (writeLock) { forget(l.id); } }
                    continue;
                }
                boolean fresh = !l.up;
                l.up = true;
                if (!fresh && !due) continue;
                if (local == null) synchronized (writeLock) { local = localEntries(); }
                RouterWire.notify(node, l.id, locations(true, local));
            }
        } catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Router location refresh failed", error); }
    }
    /** Caller holds writeLock. */
    private List<Entry> localEntries() {
        List<Entry> entries = new ArrayList<>(nodes.size());
        nodes.forEach((id, a) -> entries.add(new Entry(id, a.epoch, a.service)));
        return entries;
    }
    /** Caller holds writeLock. Drops everything {@code routerId} reported. */
    private void forget(int routerId) {
        remote.values().removeIf(l -> l.router == routerId);
    }
    /**
     * Tells the mesh about one local change; a lost report is repaired by the next complete refresh. Each
     * send gets its own retained duplicate, because RPC consumes the body it accepts.
     */
    private void report(RpcNode node, RpcRequest message) {
        try {
            for (Link l : routers) {
                if (!node.isPeerConnected(l.id)) continue;
                RouterWire.notify(node, l.id, RouterWire.packet(message.routeKey(), message.body().retainedDuplicate()));
            }
        } finally { release(message.body()); }
    }

    // ---------------------------------------------------------------- RPC entry (Netty threads)

    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() == COMMAND) relay(source, slot, request);
        else direct.onRequest(source, slot, request);
    }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        direct.onResponse(source, command, response, callback);
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        direct.onFail(target, command, error, callback);
    }

    private void relay(int source, int slot, RpcRequest request) {
        RpcNode node = rpc;
        if (closed || node == null) throw new IllegalStateException("Router not running");
        ByteBuf b = request.body().duplicate();
        int op = operation(b);
        boolean fromRouter = isRouter(source);
        if (op == DATA) { route(node, source, slot, request, data(b), fromRouter); return; }
        if (request.requestId() != 0) throw new IllegalArgumentException("Router control operations are notifies");
        switch (op) {
            case ATTACH -> {
                if (fromRouter) throw new IllegalArgumentException("A Router does not attach as a service");
                need(b, 12);
                long epoch = b.readLong(); int service = b.readInt(); end(b);
                attach(node, source, epoch, service);
            }
            case DETACH -> {
                if (fromRouter) throw new IllegalArgumentException("A Router does not detach as a service");
                need(b, 8);
                long epoch = b.readLong(); end(b);
                detach(node, source, epoch);
            }
            case LOCATIONS -> {
                if (!fromRouter) throw new IllegalArgumentException("Locations accepted only from a declared Router");
                Report report = locations(b);
                synchronized (writeLock) {
                    if (closed) return;
                    if (report.replace()) forget(source);
                    for (Entry e : report.entries())
                        if (!nodes.containsKey(e.nodeId())) remote.put(e.nodeId(), new Location(e.nodeEpoch(), e.serviceId(), source));
                }
            }
            case UNLOCATE -> {
                if (!fromRouter) throw new IllegalArgumentException("Unlocate accepted only from a declared Router");
                List<Entry> entries = unlocate(b);
                synchronized (writeLock) {
                    for (Entry e : entries) {
                        Location l = remote.get(e.nodeId());
                        if (l != null && l.router == source && l.epoch == e.nodeEpoch()) remote.remove(e.nodeId(), l);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown Router operation");
        }
    }
    /** Records the Node announced on the connection from {@code id}; a new incarnation replaces the old one. */
    private void attach(RpcNode node, int id, long epoch, int service) {
        if (epoch == 0 || service <= 0) throw new IllegalArgumentException("Invalid Node attachment");
        Attachment current = nodes.get(id);
        if (current != null && current.epoch == epoch && current.service == service) return;
        synchronized (writeLock) {
            if (!nodes.containsKey(id) && nodes.size() >= MAX_NODES) {
                nodes.forEach((key, a) -> { if (!node.isPeerConnected(key)) removeAttachment(key, a); });
                if (nodes.size() >= MAX_NODES) throw new IllegalStateException("Router attachment capacity exceeded");
            }
            Attachment next = new Attachment(epoch, service), old = nodes.put(id, next);
            if (old != null && old.service != service) unindex(id, old.service);
            services.computeIfAbsent(service, ignored -> ConcurrentHashMap.newKeySet()).add(id);
            // A Node that is here is not wherever another Router last reported it.
            remote.remove(id);
        }
        if (closed) { clearState(); return; }
        report(node, locations(false, List.of(new Entry(id, epoch, service))));
    }
    private void detach(RpcNode node, int id, long epoch) {
        boolean removed;
        synchronized (writeLock) {
            Attachment current = nodes.get(id);
            removed = current != null && current.epoch == epoch && removeAttachment(id, current);
        }
        if (removed && !closed) report(node, unlocate(List.of(new Entry(id, epoch, 0))));
    }
    /** Caller holds writeLock. */
    private boolean removeAttachment(int id, Attachment a) {
        if (!nodes.remove(id, a)) return false;
        unindex(id, a.service);
        return true;
    }
    /** Caller holds writeLock. */
    private void unindex(int id, int service) {
        Set<Integer> ids = services.get(service);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) services.remove(service, ids);
    }
    private void clearState() {
        synchronized (writeLock) { nodes.clear(); services.clear(); remote.clear(); }
    }

    // ---------------------------------------------------------------- data path

    /**
     * Relays one DATA payload. {@code outer} is a Call only for a DIRECT request a service sent; a frame that
     * arrived {@code fromRouter} is already on its second hop and is only ever delivered locally.
     */
    private void route(RpcNode node, int peer, int slot, RpcRequest outer, Data d, boolean fromRouter) {
        if (fromRouter) {
            // The frame locates its own sender on the Router it came from, exactly as a service's routed
            // message announces it here. Without this a response could need the caller's location before the
            // report carrying it arrived: the two travel the same connection but not the same Slot.
            locate(peer, d.source(), d.sourceEpoch(), d.sourceService());
        } else {
            if (d.source() != peer) throw new IllegalArgumentException("Routed source must be the sending Node");
            attach(node, peer, d.sourceEpoch(), d.sourceService());   // a caller is reachable for its response at once
        }
        switch (d.mode()) {
            case DIRECT -> {
                if (!fromRouter && d.requestId() != 0) throw new IllegalArgumentException("Inner requestId must be zero");
                // Only the caller's own Router stamps its inbound call ID; the second hop keeps what it got.
                int callId = fromRouter ? d.requestId() : outer.requestId();
                Attachment t = attached(d.target(), d.targetEpoch());
                if (t != null) {
                    RpcSendStatus status = send(node, d.target(), t, forward(d, d.target(), t.epoch, callId));
                    if (status != RpcSendStatus.ACCEPTED) fail(node, peer, slot, outer, error(status));
                    return;
                }
                Location l = fromRouter ? null : located(node, d.target(), d.targetEpoch());
                if (l == null) {
                    targetNotFound.increment();
                    fail(node, peer, slot, outer, RpcErrorCodes.PEER_NOT_FOUND);
                    return;
                }
                RpcSendStatus status = pass(node, l.router, forward(d, d.target(), l.epoch, callId));
                if (status != RpcSendStatus.ACCEPTED) fail(node, peer, slot, outer, error(status));
            }
            case BROADCAST -> {
                if (outer.requestId() != 0 || d.requestId() != 0) throw new IllegalArgumentException("Broadcast must be a notify");
                Set<Integer> ids = services.get(d.targetService());
                if (ids != null) for (int id : ids) {
                    Attachment a = nodes.get(id);
                    if (a != null && a.service == d.targetService()) send(node, id, a, forward(d, id, a.epoch, 0));
                }
                if (fromRouter) return;                 // a broadcast from the mesh stays local
                for (Link l : routers)
                    if (node.isPeerConnected(l.id)) pass(node, l.id, forward(d, 0, 0, 0));
            }
            case RESPONSE -> {
                if (outer.requestId() != 0) throw new IllegalArgumentException("Routed response must be a notify");
                Attachment t = attached(d.target(), d.targetEpoch());
                if (t != null) {
                    // A caller that left or restarted has nothing waiting. The Router never saw the connection
                    // the call left on, so the response takes any of the caller's writable Slots.
                    boolean sent = forwardResponse(node, d.target(),
                            new RpcResponse(d.requestId(), 0, null, forward(d, d.target(), t.epoch, 0).body())) == RpcSendStatus.ACCEPTED;
                    (sent ? forwarded : responsesDropped).increment();
                    return;
                }
                Location l = fromRouter ? null : located(node, d.target(), d.targetEpoch());
                if (l == null) { responsesDropped.increment(); return; }
                if (pass(node, l.router, forward(d, d.target(), l.epoch, 0)) != RpcSendStatus.ACCEPTED)
                    responsesDropped.increment();
            }
            default -> throw new IllegalArgumentException("Unknown routed mode");
        }
    }
    /** Records where {@code routerId} says a Node is; a Node attached here is never displaced by a report. */
    private void locate(int routerId, int nodeId, long epoch, int service) {
        Location current = remote.get(nodeId);
        if (current != null && current.router == routerId && current.epoch == epoch && current.service == service) return;
        synchronized (writeLock) {
            if (closed || nodes.containsKey(nodeId)) return;
            remote.put(nodeId, new Location(epoch, service, routerId));
        }
    }
    /** The attachment here for an exact incarnation, or null; any epoch when {@code epoch} is 0. */
    private Attachment attached(int nodeId, long epoch) {
        Attachment a = nodes.get(nodeId);
        return a != null && (epoch == 0 || epoch == a.epoch) ? a : null;
    }
    /** A usable reported location for an exact incarnation, or null; unusable while its Router is disconnected. */
    private Location located(RpcNode node, int nodeId, long epoch) {
        Location l = remote.get(nodeId);
        if (l == null || (epoch != 0 && epoch != l.epoch)) return null;
        return node.isPeerConnected(l.router) ? l : null;
    }
    private static int error(RpcSendStatus status) {
        return status == RpcSendStatus.PEER_NOT_FOUND ? RpcErrorCodes.PEER_NOT_FOUND : RpcErrorCodes.UNAVAILABLE;
    }
    /** Delivers to a Node attached here; a refused send to a Node whose connection is gone forgets it. */
    private RpcSendStatus send(RpcNode node, int id, Attachment a, RpcRequest message) {
        RpcSendStatus status = RouterWire.notify(node, id, message);
        if (status == RpcSendStatus.ACCEPTED) { forwarded.increment(); return status; }
        sendRefused.increment();
        if (!node.isPeerConnected(id)) {
            boolean removed;
            synchronized (writeLock) { removed = removeAttachment(id, a); }
            if (removed && !closed) report(node, unlocate(List.of(new Entry(id, a.epoch, 0))));
        }
        return status;
    }
    /** Hands a message to the next Router; a refused send to a Router that is gone forgets what it reported. */
    private RpcSendStatus pass(RpcNode node, int routerId, RpcRequest message) {
        RpcSendStatus status = RouterWire.notify(node, routerId, message);
        if (status == RpcSendStatus.ACCEPTED) { passed.increment(); return status; }
        sendRefused.increment();
        if (!node.isPeerConnected(routerId)) synchronized (writeLock) { forget(routerId); }
        return status;
    }
    /** Answers the caller's outer Call at once; a Notify has nobody waiting. */
    private void fail(RpcNode node, int peer, int slot, RpcRequest outer, int error) {
        if (outer.requestId() != 0) reply(node, peer, slot, new RpcResponse(outer.requestId(), error, null, null));
    }
    private RpcSendStatus reply(RpcNode node, int id, int slot, RpcResponse message) {
        try { return node.reply(id, slot, message); }
        catch (RuntimeException error) {
            release(message.body());
            LOG.log(System.Logger.Level.WARNING, "Router reply failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
    }
    private RpcSendStatus forwardResponse(RpcNode node, int id, RpcResponse message) {
        try { return node.forwardResponse(id, 0, message); }
        catch (RuntimeException error) {
            release(message.body());
            LOG.log(System.Logger.Level.WARNING, "Router response relay failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
    }
}
