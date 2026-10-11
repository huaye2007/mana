package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcErrorCodes;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.route.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.managame.router.node.RouterWire.*;
import static cn.managame.router.error.RouterErrorCodes.*;

/**
 * Router role on the application-owned RpcNode.
 *
 * <p>State: this Router is the only authority for its local bucket (services registered here and their
 * bindings). Every other configured Router's bucket is kept as a read-only replica fed by that Router's
 * ordered stream. Local bucket and replicas mirror into one lock-free {@link RouteIndex}; routing a
 * message never takes a lock.
 *
 * <p>Synchronization: per Router pair and direction, the authority streams one session over the single
 * Router Slot as fire-and-forget notifies: BEGIN, snapshot chunks, END, then batched DELTA frames, each
 * with a consecutive sequence number. TCP on one Slot already orders frames, so nothing is acknowledged.
 * The receiver checks continuity; on any gap, malformed frame or connection loss it keeps serving the
 * last committed replica and asks for a new session with SYNC. Periodic STATE frames expose tail loss.
 *
 * <p>Data: responses and routed errors carry the caller's origin Router and return there directly, so the
 * data path depends only on the authoritative bucket at each end, never on replica freshness.
 */
final class RouterEngine implements RpcHandler {
    private static final System.Logger LOG = System.getLogger(RouterEngine.class.getName());
    static final long TICK_MILLIS = 20;
    static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(1), SYNC_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** A live replica that hears nothing (not even STATE) for this long asks for a new session. */
    static final long SILENCE_NANOS = 3 * HEARTBEAT_NANOS;
    static final int MAX_PENDING_OPS = 100_000;
    private static final Object STATE_MARKER = new Object();

    private final long epoch;
    private volatile RpcNode rpc;
    private volatile boolean closed;
    private int self;
    /** Guards the local bucket, discovery fences and the order in which local changes enter each stream. */
    private final Object authority = new Object();
    private volatile RouterTable local;
    final RouteIndex index = new RouteIndex();
    private final ConcurrentHashMap<Integer, Link> links = new ConcurrentHashMap<>();
    private record RemovedNode(int nodeId, long epoch) {}
    private final Set<RemovedNode> removedNodes = new HashSet<>();
    /** Session numbers, guarded by authority; engine-wide so a re-registered Link never reuses one. */
    private long sessions;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "router-maintenance");
        thread.setDaemon(true);
        return thread;
    });

    private record Op(int kind, NodeRegistration node, BindingKey key, RouteBinding owner) {}
    /** Snapshot captured atomically with the local bucket; chunks are encoded lazily while sending. */
    private static final class Snapshot {
        final NodeRegistration[] nodes; final BindingKey[] keys; final RouteBinding[] owners;
        int phase, cursor; // phase 0 BEGIN, 1 NODES, 2 BINDINGS, 3 END
        Snapshot(NodeRegistration[] nodes, BindingKey[] keys, RouteBinding[] owners) { this.nodes = nodes; this.keys = keys; this.owners = owners; }
    }

    /** One configured Router: our outbound session to it and our replica of its bucket. */
    private final class Link {
        final int id;
        // Outbound stream, guarded by this Link's monitor.
        long session, seq, sessionStart, lastSent;
        int pendingOps, encodedOps;
        final ArrayDeque<Object> queue = new ArrayDeque<>();
        final AtomicBoolean pumping = new AtomicBoolean(), retryScheduled = new AtomicBoolean();
        // Inbound replica, guarded by replicaLock.
        final Object replicaLock = new Object();
        RouterTable table, staging;
        int stageNodes, stageBindings;
        long inEpoch, inSession, expectSeq, brokenEpoch, brokenSession, lastSync, lastProgress;
        boolean live;
        Link(int id) {
            this.id = id;
            long now = System.nanoTime();
            lastSync = lastProgress = now - SYNC_RETRY_NANOS; lastSent = now - HEARTBEAT_NANOS;
        }
    }

    RouterEngine(long routerEpoch) {
        if (routerEpoch == 0) throw new IllegalArgumentException("routerEpoch must be nonzero");
        epoch = routerEpoch;
    }
    /** The Router role starts no RPC calls; every callback belongs to the application handler. */
    boolean ownsCallback(RpcCallback<?> callback) { return false; }

    void start(RpcNode rpcNode) {
        synchronized (authority) {
            if (closed || rpc != null) throw new IllegalStateException("Router already started or closed");
            self = rpcNode.nodeId();
            local = new RouterTable(self, index);
            rpc = Objects.requireNonNull(rpcNode);
            maintenance.scheduleWithFixedDelay(this::maintain, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
    private RpcNode running(String action) {
        RpcNode node = rpc;
        if (node == null) throw new IllegalStateException("Start routing before " + action);
        if (closed) throw new IllegalStateException("Router closed");
        return node;
    }

    // ---------------------------------------------------------------- membership and discovery

    /** Routing membership only. The application configures the ordinary RPC Peer separately. */
    public void registerRouter(int id) {
        RpcNode node = running("membership changes");
        if (id == 0 || id == node.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        if (node.peerSlotCount(id) > 1) throw new IllegalStateException("Router Peer requires one RPC Slot");
        synchronized (authority) {
            if (closed) throw new IllegalStateException("Router closed");
            if (local.nodes.containsKey(id)) throw new IllegalArgumentException("ID already belongs to a Node");
            links.computeIfAbsent(id, Link::new);
        }
        Link l = links.get(id);
        if (l != null && connected(id)) requestSync(l);
    }
    public void unregisterRouter(int id) {
        RpcNode node = running("membership changes");
        if (id == 0 || id == node.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        Link l;
        synchronized (authority) {
            if (closed) throw new IllegalStateException("Router closed");
            l = links.remove(id);
        }
        if (l == null) return;
        synchronized (l) { l.session = 0; l.queue.clear(); l.pendingOps = 0; }
        synchronized (l.replicaLock) {
            if (l.table != null) l.table.unpublish();
            l.table = null; l.staging = null; l.live = false;
        }
    }
    /** Discovery-owned offline notification, scoped to the exact service incarnation. */
    public void removeNode(int id, long nodeEpoch) {
        RpcNode node = running("discovery changes");
        if (id == 0 || id == node.nodeId() || nodeEpoch == 0) throw new IllegalArgumentException("invalid Node incarnation");
        synchronized (authority) {
            if (closed) throw new IllegalStateException("Router closed");
            RemovedNode removed = new RemovedNode(id, nodeEpoch);
            if (!removedNodes.contains(removed) && removedNodes.size() >= RouterTable.MAX_NODES)
                throw new RejectedExecutionException("Discovery removal fence capacity exceeded");
            removedNodes.add(removed);
            NodeRegistration n = local.nodes.get(id);
            if (n == null || n.nodeEpoch() != nodeEpoch) return;
            local.remove(id, nodeEpoch);
            publish(new Op(REMOVE_NODE, n, null, null));
        }
        pumpAll();
    }

    // ---------------------------------------------------------------- lock-free queries

    public Map<BindingKey, RouteBinding> localBindings() {
        RouterTable t = local;
        return t == null ? Map.of() : Map.copyOf(t.bindings);
    }
    public Optional<RouteBinding> resolve(int serviceId, long bindingKey) {
        RouteIndex.Target target = index.resolve(new BindingKey(serviceId, bindingKey));
        return target == null ? Optional.empty() : Optional.of(new RouteBinding(target.nodeId(), target.nodeEpoch()));
    }
    public Set<Integer> serviceNodes(int serviceId) {
        if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
        Set<Integer> result = new HashSet<>();
        for (Integer id : index.nodes.keySet()) {
            RouteIndex.NodeEntry located = index.locate(id);
            if (located != null && located.node().serviceId() == serviceId) result.add(id);
        }
        return Set.copyOf(result);
    }
    /** Connected over exactly one Slot and holding a complete, gap-free replica of that Router's bucket. */
    public boolean isRouterReady(int id) {
        Link l = links.get(id);
        if (l == null || closed || rpc == null || !connected(id)) return false;
        synchronized (l.replicaLock) { return l.live; }
    }
    private boolean connected(int id) {
        RpcNode node = rpc;
        return node != null && node.peerSlotCount(id) == 1 && node.isPeerConnected(id);
    }

    // ---------------------------------------------------------------- maintenance

    void maintain() {
        try {
            if (closed || rpc == null) return;
            long now = System.nanoTime();
            for (Link l : links.values()) {
                if (!connected(l.id)) {
                    // Frames already accepted on a lost connection may be gone: both directions restart.
                    synchronized (l) { l.session = 0; l.queue.clear(); l.pendingOps = 0; }
                    synchronized (l.replicaLock) { if (l.live || l.staging != null) markBroken(l); }
                    continue;
                }
                boolean sync;
                synchronized (l.replicaLock) {
                    // A silent stream may have lost frames on a connection replaced before we noticed.
                    if (l.live && now - l.lastProgress >= SILENCE_NANOS) markBroken(l);
                    // A snapshot that stops arriving is abandoned so SYNC names it and the authority restarts it.
                    if (l.staging != null && now - l.lastProgress >= SYNC_RETRY_NANOS) markBroken(l);
                    sync = !l.live && now - Math.max(l.lastSync, l.lastProgress) >= SYNC_RETRY_NANOS;
                }
                if (sync) requestSync(l);
                // The authority (re)starts its session as soon as the Router Slot is usable, without waiting for SYNC.
                boolean idle;
                synchronized (l) { idle = l.session == 0; }
                if (idle) synchronized (authority) {
                    if (closed || links.get(l.id) != l) continue;
                    synchronized (l) { if (l.session != 0) idle = false; }
                    if (idle) startSession(l);
                }
                synchronized (l) {
                    if (l.session != 0 && l.queue.isEmpty() && now - l.lastSent >= HEARTBEAT_NANOS) l.queue.add(STATE_MARKER);
                }
                pump(l);
            }
        } catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Router maintenance failed", error); }
    }

    // ---------------------------------------------------------------- outbound stream (this Router is authority)

    /** Caller holds authority: the snapshot and every later delta are ordered against local changes. */
    private void startSession(Link l) {
        NodeRegistration[] nodes = local.nodes.values().toArray(NodeRegistration[]::new);
        int count = local.bindings.size();
        BindingKey[] keys = new BindingKey[count]; RouteBinding[] owners = new RouteBinding[count];
        int i = 0;
        for (var e : local.bindings.entrySet()) { keys[i] = e.getKey(); owners[i] = e.getValue(); i++; }
        if (i != count) { keys = Arrays.copyOf(keys, i); owners = Arrays.copyOf(owners, i); }
        synchronized (l) {
            l.queue.clear(); l.pendingOps = 0;
            l.session = ++sessions; l.seq = 0; l.sessionStart = System.nanoTime();
            l.queue.add(new Snapshot(nodes, keys, owners));
        }
    }
    /** Caller holds authority and has already applied the change to the local bucket. */
    private void publish(Op op) {
        for (Link l : links.values()) {
            boolean restart;
            synchronized (l) {
                if (l.session == 0) continue;
                restart = l.pendingOps >= MAX_PENDING_OPS;
                if (!restart) { l.queue.add(op); l.pendingOps++; }
            }
            // An overflowing stream is replaced by a fresh snapshot, which already contains this change.
            if (restart) {
                LOG.log(System.Logger.Level.WARNING, "Router stream to {0} overflowed; restarting with a snapshot", l.id);
                startSession(l);
            }
        }
    }
    private void pumpAll() { for (Link l : links.values()) pump(l); }

    /** Sends queued frames in order until the queue is empty or the Slot refuses (retried by maintenance). */
    private void pump(Link l) {
        RpcNode node = rpc;
        if (node == null || closed) return;
        while (l.pumping.compareAndSet(false, true)) {
            boolean refused = false;
            try {
                while (!closed) {
                    RpcRequest frame; Object head; long session;
                    synchronized (l) {
                        head = l.queue.peek();
                        if (head == null || l.session == 0) break;
                        frame = encode(l, head);
                        session = l.session;
                    }
                    RpcSendStatus status;
                    try { status = node.notify(l.id, frame); }
                    catch (RuntimeException error) {
                        LOG.log(System.Logger.Level.WARNING, "Router stream send failed", error);
                        status = RpcSendStatus.UNAVAILABLE;
                    }
                    if (status != RpcSendStatus.ACCEPTED) { refused = true; break; }
                    synchronized (l) {
                        // A reset replaced the queue: the frame belonged to an abandoned session.
                        if (l.session == session && l.queue.peek() == head) advance(l, head);
                    }
                }
            } finally { l.pumping.set(false); }
            if (refused) { retrySoon(l); return; }
            synchronized (l) { if (l.queue.isEmpty() || l.session == 0) return; }
        }
    }
    /** A full write buffer drains within milliseconds; poll it briefly instead of waiting for the next tick. */
    private void retrySoon(Link l) {
        if (closed || !l.retryScheduled.compareAndSet(false, true)) return;
        try {
            maintenance.schedule(() -> { l.retryScheduled.set(false); if (connected(l.id)) pump(l); }, 1, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) { l.retryScheduled.set(false); }
    }
    /** Encodes the head item without consuming it; advance consumes it after the Slot accepts the frame. */
    private RpcRequest encode(Link l, Object head) {
        if (head == STATE_MARKER) return packet(sync(STATE, epoch, l.session, l.seq, 0));
        if (head instanceof Snapshot s) {
            return switch (s.phase) {
                case 0 -> packet(sync(BEGIN, epoch, l.session, 0, 8).writeInt(s.nodes.length).writeInt(s.keys.length));
                case 1 -> {
                    int n = Math.min(CHUNK, s.nodes.length - s.cursor);
                    ByteBuf b = sync(NODES, epoch, l.session, l.seq + 1, 2 + n * NODE_BYTES).writeShort(n);
                    for (int i = 0; i < n; i++) node(b, s.nodes[s.cursor + i]);
                    yield packet(b);
                }
                case 2 -> {
                    int n = Math.min(CHUNK, s.keys.length - s.cursor);
                    ByteBuf b = sync(BINDINGS, epoch, l.session, l.seq + 1, 2 + n * BINDING_BYTES).writeShort(n);
                    for (int i = 0; i < n; i++) binding(b, s.keys[s.cursor + i], s.owners[s.cursor + i]);
                    yield packet(b);
                }
                default -> packet(sync(END, epoch, l.session, l.seq + 1, 0));
            };
        }
        // Consecutive operations at the head travel as one DELTA frame.
        List<Op> ops = new ArrayList<>(Math.min(CHUNK, l.queue.size()));
        for (Object item : l.queue) {
            if (!(item instanceof Op op) || ops.size() == CHUNK) break;
            ops.add(op);
        }
        ByteBuf b = sync(DELTA, epoch, l.session, l.seq + 1, 2 + ops.size() * (1 + BINDING_BYTES)).writeShort(ops.size());
        for (Op op : ops) {
            b.writeByte(op.kind());
            switch (op.kind()) {
                case ADD_NODE -> node(b, op.node());
                case REMOVE_NODE -> b.writeInt(op.node().nodeId()).writeLong(op.node().nodeEpoch());
                default -> binding(b, op.key(), op.owner());
            }
        }
        l.encodedOps = ops.size();
        return packet(b);
    }
    private void advance(Link l, Object head) {
        l.lastSent = System.nanoTime();
        if (head == STATE_MARKER) { l.queue.poll(); return; }
        if (head instanceof Snapshot s) {
            switch (s.phase) {
                case 0 -> { s.phase = s.nodes.length > 0 ? 1 : s.keys.length > 0 ? 2 : 3; s.cursor = 0; }
                case 1 -> {
                    l.seq++; s.cursor += Math.min(CHUNK, s.nodes.length - s.cursor);
                    if (s.cursor == s.nodes.length) { s.phase = s.keys.length > 0 ? 2 : 3; s.cursor = 0; }
                }
                case 2 -> {
                    l.seq++; s.cursor += Math.min(CHUNK, s.keys.length - s.cursor);
                    if (s.cursor == s.keys.length) s.phase = 3;
                }
                default -> { l.seq++; l.queue.poll(); }
            }
            return;
        }
        l.seq++;
        for (int i = 0; i < l.encodedOps; i++) l.queue.poll();
        l.pendingOps -= l.encodedOps;
    }
    /** A peer asks for a fresh session of our bucket. Duplicate requests for a session just started are ignored. */
    private void onSync(Link l, ByteBuf b) {
        need(b, 24);
        long requester = b.readLong(), knownEpoch = b.readLong(), brokenSession = b.readLong(); end(b);
        if (requester == 0) throw new IllegalArgumentException("Invalid Router identity");
        synchronized (authority) {
            if (closed) return;
            synchronized (l) {
                if (l.session != 0 && knownEpoch == epoch && Long.compareUnsigned(l.session, brokenSession) > 0
                        && System.nanoTime() - l.sessionStart < SYNC_RETRY_NANOS) return;
            }
            startSession(l);
        }
        pump(l);
    }

    // ---------------------------------------------------------------- inbound replica (peer is authority)

    private void requestSync(Link l) {
        long knownEpoch, broken;
        synchronized (l.replicaLock) {
            l.lastSync = System.nanoTime();
            knownEpoch = l.live ? l.inEpoch : l.brokenEpoch;
            broken = l.live ? l.inSession : l.brokenSession;
        }
        try { rpc.notify(l.id, packet(control(SYNC, 26).writeLong(epoch).writeLong(knownEpoch).writeLong(broken))); }
        catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Router SYNC send failed", error); }
    }
    /** Caller holds replicaLock. The committed replica stays queryable until a new session completes. */
    private void markBroken(Link l) {
        l.staging = null; l.live = false;
        l.brokenEpoch = l.inEpoch; l.brokenSession = l.inSession;
    }
    private void onStream(Link l, int op, ByteBuf b) {
        boolean sync = false;
        synchronized (l.replicaLock) {
            try {
                SyncHeader h = syncHeader(op, b);
                if (op == BEGIN) {
                    need(b, 8);
                    int nodes = b.readInt(), bindings = b.readInt(); end(b);
                    if (nodes < 0 || nodes > RouterTable.MAX_NODES || bindings < 0 || bindings > RouterTable.MAX_BINDINGS)
                        throw new IllegalArgumentException("Snapshot capacity exceeded");
                    // A session that was already broken (or older) cannot restart: wait for the requested one.
                    if (h.epoch() == l.brokenEpoch && Long.compareUnsigned(h.session(), l.brokenSession) <= 0) return;
                    l.inEpoch = h.epoch(); l.inSession = h.session(); l.expectSeq = 1;
                    l.staging = new RouterTable(l.id, null); l.stageNodes = nodes; l.stageBindings = bindings;
                    l.live = false; l.lastProgress = System.nanoTime();
                    return;
                }
                if (h.epoch() != l.inEpoch || h.session() != l.inSession) {
                    // Leftovers of an abandoned session of the same incarnation are harmless.
                    if (h.epoch() == l.inEpoch && Long.compareUnsigned(h.session(), l.inSession) < 0) return;
                    if (l.live || l.staging != null) { markBroken(l); sync = true; }
                    return;
                }
                if (!l.live && l.staging == null) return; // waiting for the session requested by SYNC
                if (op == STATE) {
                    end(b);
                    if (!l.live || h.seq() != l.expectSeq - 1) { markBroken(l); sync = true; }
                    else l.lastProgress = System.nanoTime();
                    return;
                }
                if (h.seq() != l.expectSeq) { markBroken(l); sync = true; return; }
                switch (op) {
                    case NODES -> {
                        if (l.staging == null) throw new IllegalArgumentException("Nodes outside snapshot");
                        int count = chunk(b, NODE_BYTES);
                        for (int i = 0; i < count; i++) l.staging.add(node(b));
                        if (l.staging.nodes.size() > l.stageNodes) throw new IllegalArgumentException("Excess snapshot Nodes");
                    }
                    case BINDINGS -> {
                        if (l.staging == null) throw new IllegalArgumentException("Bindings outside snapshot");
                        int count = chunk(b, BINDING_BYTES);
                        for (int i = 0; i < count; i++) l.staging.bind(key(b), owner(b));
                        if (l.staging.bindings.size() > l.stageBindings) throw new IllegalArgumentException("Excess snapshot bindings");
                    }
                    case END -> {
                        end(b);
                        if (l.staging == null || l.staging.nodes.size() != l.stageNodes || l.staging.bindings.size() != l.stageBindings)
                            throw new IllegalArgumentException("Incomplete snapshot");
                        l.staging.publish(index, l.table);
                        l.table = l.staging; l.staging = null; l.live = true;
                        logConflicts(l.table);
                    }
                    case DELTA -> {
                        if (!l.live) throw new IllegalArgumentException("Delta before snapshot end");
                        need(b, 2);
                        int count = b.readUnsignedShort();
                        if (count < 1 || count > CHUNK) throw new IllegalArgumentException("Invalid delta size");
                        for (int i = 0; i < count; i++) applyDelta(l.table, b);
                        end(b);
                    }
                    default -> throw new IllegalArgumentException("Unknown Router stream operation");
                }
                l.expectSeq++;
                l.lastProgress = System.nanoTime();
            } catch (RuntimeException invalid) {
                LOG.log(System.Logger.Level.WARNING, "Invalid Router stream from " + l.id + "; resynchronizing", invalid);
                markBroken(l); sync = true;
            }
        }
        if (sync && System.nanoTime() - l.lastSync >= TimeUnit.MILLISECONDS.toNanos(100)) requestSync(l);
    }
    private static int chunk(ByteBuf b, int entryBytes) {
        need(b, 2);
        int count = b.readUnsignedShort();
        if (count < 1 || count > CHUNK || b.readableBytes() != count * entryBytes) throw new IllegalArgumentException("Invalid snapshot chunk");
        return count;
    }
    private void applyDelta(RouterTable table, ByteBuf b) {
        need(b, 1);
        switch (b.readUnsignedByte()) {
            case ADD_NODE -> table.add(node(b));
            case REMOVE_NODE -> { need(b, 12); table.remove(b.readInt(), b.readLong()); }
            case BIND -> { BindingKey key = key(b); RouteBinding owner = owner(b); table.bind(key, owner); logConflict(key); }
            case UNBIND -> { BindingKey key = key(b); RouteBinding owner = owner(b); table.unbind(key, owner); }
            default -> throw new IllegalArgumentException("Unknown delta operation");
        }
    }
    private void logConflict(BindingKey key) {
        if (index.owners(key) > 1) LOG.log(System.Logger.Level.ERROR, "Conflicting Router binding: {0}, winner={1}", key, index.resolve(key));
    }
    private void logConflicts(RouterTable table) { for (BindingKey key : table.bindings.keySet()) logConflict(key); }

    // ---------------------------------------------------------------- RPC entry

    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() != COMMAND) throw new IllegalArgumentException("Router accepts only Router Profile messages");
        RpcNode node = rpc;
        if (closed || node == null) throw new IllegalStateException("Router not running");
        ByteBuf b = request.body().duplicate();
        int op = operation(b);
        if (op == DATA) { route(source, request, data(b)); return; }
        Link link = links.get(source);
        if (op >= SYNC && op <= STATE) {
            // A peer that configured us first streams before we configure it: ignore until we do (SYNC then follows).
            if (link == null && request.requestId() == 0) return;
            if (link == null || request.requestId() != 0 || node.peerSlotCount(source) != 1)
                throw new IllegalArgumentException("Router stream requires a configured single-Slot Router and notify");
            if (op == SYNC) onSync(link, b); else onStream(link, op, b);
            return;
        }
        if (op == DATA_ERROR) {
            if (link == null || request.requestId() != 0) throw new IllegalArgumentException("Routed error requires a configured Router");
            need(b, 20);
            int target = b.readInt(); long targetEpoch = b.readLong(); int id = b.readInt(), error = b.readInt(); end(b);
            if (id == 0 || error <= 0) throw new IllegalArgumentException("Invalid routed error");
            NodeRegistration n = local.nodes.get(target);
            if (n != null && n.nodeEpoch() == targetEpoch) replyOwned(target, new RpcResponse(id, error, null, null));
            return;
        }
        if (link != null) throw new IllegalArgumentException("Router cannot register as service Node");
        int error;
        synchronized (authority) {
            if (closed) throw new IllegalStateException("Router not running");
            error = serviceControl(source, op, b);
        }
        // Answer the service first; streaming the change to other Routers does not delay its completion.
        if (request.requestId() != 0)
            node.reply(source, slot, request.routeKey(), new RpcResponse(request.requestId(), error, null,
                    error == 0 && (op == REGISTER || op == VERIFY) ? Unpooled.buffer(8).writeLong(epoch) : null));
        if (op != VERIFY) pumpAll();
    }
    /** Caller holds authority. */
    private int serviceControl(int source, int op, ByteBuf b) {
        if (op == REGISTER) {
            NodeRegistration n = node(b); end(b);
            if (n.nodeId() != source) throw new IllegalArgumentException("Registration source mismatch");
            if (removedNodes.contains(new RemovedNode(source, n.nodeEpoch()))) return NOT_REGISTERED;
            NodeRegistration old = local.nodes.get(source);
            if (old != null) return old.equals(n) ? 0 : BINDING_CONFLICT;
            local.add(n); publish(new Op(ADD_NODE, n, null, null)); return 0;
        }
        if (op == VERIFY) {
            // expected Router epoch, the service's nodeEpoch, zero revision
            need(b, 24);
            long expectedRouter = b.readLong(), nodeEpoch = b.readLong(), revision = b.readLong(); end(b);
            if (revision != 0) throw new IllegalArgumentException("Service revision must be zero");
            NodeRegistration n = local.nodes.get(source);
            return expectedRouter == epoch && n != null && n.nodeEpoch() == nodeEpoch ? 0 : NOT_REGISTERED;
        }
        need(b, 8);
        long nodeEpoch = b.readLong();
        NodeRegistration n = local.nodes.get(source);
        if (op == DETACH) {
            end(b);
            if (n == null || n.nodeEpoch() != nodeEpoch) return NOT_REGISTERED;
            local.remove(source, nodeEpoch); publish(new Op(REMOVE_NODE, n, null, null)); return 0;
        }
        if (op != CLIENT_BIND && op != CLIENT_UNBIND) throw new IllegalArgumentException("Unknown Router operation");
        need(b, 12);
        int service = b.readInt(); long value = b.readLong(); end(b);
        if (n == null || n.nodeEpoch() != nodeEpoch) return NOT_REGISTERED;
        if (service != n.serviceId()) return INVALID_SERVICE;
        BindingKey key = new BindingKey(service, value); RouteBinding owner = new RouteBinding(source, nodeEpoch);
        if (op == CLIENT_BIND) {
            RouteIndex.Target current = index.resolve(key);
            if (current != null && (current.nodeId() != source || current.nodeEpoch() != nodeEpoch)) { logConflict(key); return BINDING_CONFLICT; }
            if (owner.equals(local.bindings.get(key))) return 0;
            local.bind(key, owner); publish(new Op(BIND, null, key, owner));
        } else {
            RouteBinding current = local.bindings.get(key);
            if (current == null) return 0;
            if (!current.equals(owner)) return BINDING_CONFLICT;
            local.unbind(key, owner); publish(new Op(UNBIND, null, key, owner));
        }
        return 0;
    }

    // ---------------------------------------------------------------- data path (no locks)

    private void route(int peer, RpcRequest outer, Envelope e) {
        boolean remote = links.containsKey(peer);
        if (remote) {
            if (e.hops() != 1 || outer.requestId() != 0 || e.origin() == 0)
                throw new IllegalArgumentException("Invalid Router-forwarded data");
            switch (e.mode()) {
                case BROADCAST -> deliverBroadcast(e);
                case DIRECT -> {
                    NodeRegistration n = local.nodes.get(e.target());
                    if (n == null || n.nodeEpoch() != e.targetEpoch()) fail(e, RpcErrorCodes.PEER_NOT_FOUND);
                    else deliver(e, n);
                }
                case RESPONSE -> deliverResponse(e);
                default -> throw new IllegalArgumentException("Dynamic routes must be resolved at the source Router");
            }
            return;
        }
        NodeRegistration source = local.nodes.get(e.source());
        if (source == null || source.nodeEpoch() != e.sourceEpoch() || peer != e.source() || e.hops() != 0)
            throw new IllegalArgumentException("Unregistered/spoofed routed source");
        if (e.mode() == RESPONSE) {
            // The responder names the caller's Router; older callers without it are located through the index.
            int destination = e.origin();
            if (destination == 0) {
                RouteIndex.NodeEntry located = index.locate(e.target());
                if (located == null) return;
                destination = located.router();
            }
            if (destination == self) deliverResponse(e);
            else if (links.containsKey(destination))
                forwardData(destination, copy(e, RESPONSE, destination, e.target(), e.targetEpoch(), 1));
            return;
        }
        RpcRequest r = (RpcRequest) e.message();
        if (r.requestId() != 0) throw new IllegalArgumentException("Client inner requestId must be zero");
        e = new Envelope(e.mode(), self, e.source(), e.sourceEpoch(), e.target(), e.targetEpoch(), e.service(), e.key(), 0,
                new RpcRequest(r.command(), outer.requestId(), r.routeKey(), r.businessIdType(), r.businessId(), r.metadata(), r.body()));
        if (e.mode() == BROADCAST) {
            if (outer.requestId() != 0 || e.service() <= 0) throw new IllegalArgumentException("Broadcast requires notify/service");
            deliverBroadcast(e);
            for (Link l : links.values()) forwardData(l.id, copy(e, BROADCAST, self, 0, 0, 1));
            return;
        }
        RouteIndex.NodeEntry target;
        if (e.mode() == DYNAMIC) {
            RouteIndex.Target owner = index.resolve(new BindingKey(e.service(), e.key()));
            if (owner == null) { fail(e, ROUTE_NOT_FOUND); return; }
            target = index.locate(owner.nodeId());
        } else target = index.locate(e.target());
        if (target == null || (e.mode() == DIRECT && e.targetEpoch() != 0 && target.node().nodeEpoch() != e.targetEpoch())) {
            fail(e, RpcErrorCodes.PEER_NOT_FOUND); return;
        }
        if (target.router() == self) {
            NodeRegistration n = local.nodes.get(target.node().nodeId());
            if (n == null || n.nodeEpoch() != target.node().nodeEpoch()) fail(e, RpcErrorCodes.PEER_NOT_FOUND);
            else deliver(e, n);
            return;
        }
        if (!links.containsKey(target.router())) { fail(e, RpcErrorCodes.UNAVAILABLE); return; }
        RpcSendStatus status = forwardData(target.router(),
                copy(e, DIRECT, self, target.node().nodeId(), target.node().nodeEpoch(), 1));
        if (status != RpcSendStatus.ACCEPTED) fail(e, sendError(status));
    }
    private void deliverBroadcast(Envelope e) {
        if (((RpcRequest) e.message()).requestId() != 0 || e.service() <= 0) throw new IllegalArgumentException("Broadcast requires notify/service");
        for (NodeRegistration n : local.nodes.values()) {
            if (n.serviceId() != e.service()) continue;
            RouteIndex.NodeEntry located = index.locate(n.nodeId());
            if (located != null && located.router() == self) deliver(e, n);
        }
    }
    private void deliver(Envelope e, NodeRegistration n) {
        RpcSendStatus status = forwardData(n.nodeId(), copy(e, DIRECT, e.origin(), n.nodeId(), n.nodeEpoch(), 1));
        if (status != RpcSendStatus.ACCEPTED) fail(e, sendError(status));
    }
    private void deliverResponse(Envelope e) {
        NodeRegistration n = local.nodes.get(e.target());
        if (n == null || n.nodeEpoch() != e.targetEpoch()) return;
        RpcResponse response = (RpcResponse) e.message();
        RpcRequest encoded = data(copy(e, RESPONSE, self, n.nodeId(), n.nodeEpoch(), 1));
        replyOwned(n.nodeId(), new RpcResponse(response.requestId(), 0, null, encoded.body()));
    }
    /** Returns a framework error to the caller's origin Router; only calls have someone waiting. */
    private void fail(Envelope e, int error) {
        if (!(e.message() instanceof RpcRequest r) || r.requestId() == 0) return;
        int origin = e.origin() == 0 ? self : e.origin();
        if (origin == self) {
            NodeRegistration n = local.nodes.get(e.source());
            if (n != null && n.nodeEpoch() == e.sourceEpoch()) replyOwned(e.source(), new RpcResponse(r.requestId(), error, null, null));
            return;
        }
        if (!links.containsKey(origin)) return;
        ByteBuf b = control(DATA_ERROR, 24).writeInt(e.source()).writeLong(e.sourceEpoch()).writeInt(r.requestId()).writeInt(error);
        try { rpc.notify(origin, packet(b)); }
        catch (RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Routed error send failed", failure); }
    }
    private static int sendError(RpcSendStatus status) {
        return status == RpcSendStatus.PEER_NOT_FOUND ? RpcErrorCodes.PEER_NOT_FOUND : RpcErrorCodes.UNAVAILABLE;
    }
    private RpcSendStatus forwardData(int id, Envelope e) {
        RpcRequest message;
        try { message = data(e); }
        catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "Routed data encoding failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
        try { return rpc.notify(id, message); }
        catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "Routed data send failed", error);
            return RpcSendStatus.UNAVAILABLE;
        } finally { if (message.body() != null && message.body().refCnt() > 0) message.body().release(); }
    }
    private void replyOwned(int id, RpcResponse message) {
        try { rpc.reply(id, 0, 0, message); }
        catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Routed reply failed", error); }
        finally { if (message.body() != null && message.body().refCnt() > 0) message.body().release(); }
    }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        throw new IllegalStateException("Router never starts calls");
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {}

    void close() {
        synchronized (authority) {
            if (closed) return;
            closed = true;
            maintenance.shutdownNow();
            for (Link l : links.values()) {
                synchronized (l) { l.session = 0; l.queue.clear(); }
                synchronized (l.replicaLock) { l.table = null; l.staging = null; l.live = false; }
            }
            links.clear(); removedNodes.clear();
            if (local != null) local.clear();
            index.clear();
        }
    }
}
