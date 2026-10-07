package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcErrorCodes;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.route.*;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.*;
import static cn.managame.router.node.RouterWire.*;
import static cn.managame.router.error.RouterErrorCodes.*;

/** Full-mesh routing state and protocol on the application-owned RpcNode. */
final class RouterEngine implements RpcHandler {
    private static final System.Logger LOG = System.getLogger(RouterEngine.class.getName());
    private final Object lock = new Object();
    private final long epoch;
    private long revision;
    private volatile RpcNode rpc;
    private final RouterTable local = new RouterTable();
    private final Map<Integer, Peer> routers = new HashMap<>();
    private final Set<Integer> configuredRouters = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private record RemovedNode(int nodeId, long epoch) {}
    private final Set<RemovedNode> removedNodes = new HashSet<>();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "router-sync-retry");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;
    private static final class Peer {
        long epoch, lastVerification, revision, stagingRevision;
        RouterTable table, staging;
        int nodeCount, bindingCount;
        boolean sent;
        boolean sending;
        boolean synchronizedOut;
        boolean synchronizedIn;
        boolean helloReceived;
        final ArrayDeque<RpcRequest> outgoing = new ArrayDeque<>();
        Peer(long epoch) { this.epoch = epoch; }
    }
    private record Control(int id, Peer peer, int operation) implements RpcCallback<Object> {
        @Override public void onResponse(Object ignored) {}
    }
    private record Location(int router, NodeRegistration node) {}

    RouterEngine(long routerEpoch) {
        if (routerEpoch == 0) throw new IllegalArgumentException("routerEpoch must be nonzero");
        epoch = routerEpoch;
    }
    boolean ownsCallback(RpcCallback<?> callback) { return callback instanceof Control; }
    void start(RpcNode rpcNode) {
        synchronized (lock) {
            if (closed || rpc != null) throw new IllegalStateException("Router already started or closed");
            rpc = Objects.requireNonNull(rpcNode);
            retries.scheduleWithFixedDelay(this::maintain, 100, 100, TimeUnit.MILLISECONDS);
        }
    }
    private void maintain() {
        try {
            synchronized (lock) {
                if (closed) return;
                for (int id : configuredRouters) {
                    Peer p = routers.get(id);
                    if (!rpc.isPeerConnected(id) || rpc.peerSlotCount(id) != 1) {
                        if (p != null && p.sent) reset(id, p);
                        continue;
                    }
                    p = routers.get(id);
                    if (p == null) { p = new Peer(0); routers.put(id, p); }
                    if (!p.sent) snapshot(id, p);
                    else if (p.synchronizedIn && p.synchronizedOut && !p.sending && p.outgoing.isEmpty()
                            && System.nanoTime() - p.lastVerification >= TimeUnit.SECONDS.toNanos(1)) {
                        p.lastVerification = System.nanoTime();
                        sendControl(id, control(VERIFY).writeLong(p.epoch).writeLong(epoch).writeLong(revision));
                    }
                }
            }
            repair();
        } catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Router maintenance failed", error); }
    }
    private void reset(int id, Peer old) {
        release(old); Peer replacement = new Peer(old.epoch); replacement.table = old.table;
        routers.put(id, replacement);
    }

    /** Routing membership only. The application configures the ordinary RPC Peer separately. */
    public void registerRouter(int id) {
        if (rpc == null) throw new IllegalStateException("Start routing before membership changes");
        if (id == 0 || id == rpc.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        if (rpc.peerSlotCount(id) > 1) throw new IllegalStateException("Router Peer requires one RPC Slot");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Router closed");
            if (local.nodes.containsKey(id)) throw new IllegalArgumentException("ID already belongs to a Node");
            configuredRouters.add(id);
        }
        if (rpc.isPeerConnected(id)) onConnected(id);
    }
    public void unregisterRouter(int id) {
        if (rpc == null) throw new IllegalStateException("Start routing before membership changes");
        if (id == 0 || id == rpc.nodeId()) throw new IllegalArgumentException("invalid Router ID");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Router closed");
            configuredRouters.remove(id); release(routers.remove(id)); failed.remove(id);
        }
    }
    public void removeNode(int id, long nodeEpoch) {
        if (rpc == null) throw new IllegalStateException("Start routing before discovery changes");
        if (id == 0 || id == rpc.nodeId() || nodeEpoch == 0) throw new IllegalArgumentException("invalid Node incarnation");
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Router closed");
            RemovedNode removed = new RemovedNode(id, nodeEpoch);
            if (!removedNodes.contains(removed) && removedNodes.size() >= RouterTable.MAX_NODES)
                throw new RejectedExecutionException("Discovery removal fence capacity exceeded");
            removedNodes.add(removed);
            NodeRegistration n = local.nodes.get(id);
            if (n == null || n.nodeEpoch() != nodeEpoch) return;
            local.remove(id, nodeEpoch);
            publish(REMOVE, b -> b.writeInt(id).writeLong(nodeEpoch));
        }
        repair();
    }
    public Map<BindingKey, RouteBinding> localBindings() {
        synchronized (lock) { return Map.copyOf(local.bindings); }
    }
    public Optional<RouteBinding> resolve(int serviceId, long bindingKey) {
        BindingKey key = new BindingKey(serviceId, bindingKey);
        synchronized (lock) { return Optional.ofNullable(resolveKey(key)); }
    }
    public Set<Integer> serviceNodes(int serviceId) {
        if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
        synchronized (lock) {
            Set<Integer> result = new HashSet<>();
            for (RouterTable table : tables()) for (NodeRegistration n : table.nodes.values()) {
                Location location = locate(n.nodeId());
                if (location != null && location.node.serviceId() == serviceId) result.add(n.nodeId());
            }
            return Set.copyOf(result);
        }
    }
    public boolean isRouterReady(int id) {
        synchronized (lock) { Peer p = routers.get(id); return !closed && rpc != null && rpc.peerSlotCount(id) == 1 && rpc.isPeerConnected(id) && p != null && p.synchronizedIn && p.synchronizedOut && !failed.contains(id); }
    }
    private void onConnected(int id) {
        if (!rpc.isPeerConnected(id)) return;
        synchronized (lock) {
            Peer p = routers.get(id);
            if (!closed && configuredRouters.contains(id) && (p == null || !p.sent) && rpc.peerSlotCount(id) == 1) {
                if (p == null) { p = new Peer(0); routers.put(id, p); }
                snapshot(id, p);
            }
        }
        repair();
    }
    private void snapshot(int id, Peer p) {
        p.sent = true;
        sendControl(id, control(HELLO).writeLong(epoch));
        sendControl(id, control(BEGIN).writeLong(epoch).writeInt(local.nodes.size()).writeInt(local.bindings.size()).writeLong(revision));
        List<NodeRegistration> nodes = new ArrayList<>(local.nodes.values());
        for (int start = 0; start < nodes.size() && !failed.contains(id); start += 256) {
            int count = Math.min(256, nodes.size() - start);
            ByteBuf b = control(NODE).writeLong(epoch).writeShort(count);
            for (int i = 0; i < count; i++) node(b, nodes.get(start + i));
            sendControl(id, b);
        }
        List<Map.Entry<BindingKey, RouteBinding>> bindings = new ArrayList<>(local.bindings.entrySet());
        for (int start = 0; start < bindings.size() && !failed.contains(id); start += 256) {
            int count = Math.min(256, bindings.size() - start);
            ByteBuf b = control(ENTRY).writeLong(epoch).writeShort(count);
            for (int i = 0; i < count; i++) {
                var e = bindings.get(start + i); binding(b, e.getKey(), e.getValue());
            }
            sendControl(id, b);
        }
        sendControl(id, control(END).writeLong(epoch));
    }
    private void sendControl(int id, ByteBuf b) {
        Peer p = routers.get(id);
        if (p == null || failed.contains(id) || closed) { b.release(); return; }
        if (p.outgoing.size() >= 8192) { b.release(); failed.add(id); return; }
        p.outgoing.addLast(packet(b));
        pump(id, p);
    }
    private void pump(int id, Peer p) {
        if (p.sending || p.outgoing.isEmpty() || failed.contains(id)) return;
        RpcRequest request = p.outgoing.removeFirst();
        p.sending = true;
        try {
            rpc.call(id, request, new Control(id, p, request.body().getUnsignedByte(request.body().readerIndex() + 1)));
        } catch (RuntimeException error) {
            failed.add(id);
            LOG.log(System.Logger.Level.WARNING, "Router synchronization send failed", error);
        } finally { if (request.body().refCnt() > 0) request.body().release(); }
    }
    private static void release(Peer p) {
        if (p != null) { for (RpcRequest r : p.outgoing) r.body().release(); p.outgoing.clear(); }
    }
    private void publish(int op, java.util.function.Consumer<ByteBuf> encode) {
        if (closed) return;
        revision++;
        for (int id : List.copyOf(routers.keySet())) {
            if (failed.contains(id) || !routers.get(id).sent || !rpc.isPeerConnected(id) || rpc.peerSlotCount(id) != 1) continue;
            ByteBuf b = control(op).writeLong(epoch).writeLong(revision);
            try { encode.accept(b); }
            catch (RuntimeException | Error error) { b.release(); throw error; }
            sendControl(id, b);
        }
    }
    // Repair only the routing stream. The shared RPC Peer and its ordinary pending calls remain intact.
    private void repair() {
        synchronized (lock) {
            for (int id : failed) {
                Peer old = routers.get(id);
                if (old != null && configuredRouters.contains(id)) reset(id, old);
                else release(routers.remove(id));
            }
            failed.clear();
        }
    }
    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() != COMMAND) throw new IllegalArgumentException("Router accepts only Router Profile messages");
        try {
            synchronized (lock) {
                if (closed || rpc == null) throw new IllegalStateException("Router not running");
                ByteBuf b = request.body().duplicate();
                int op = operation(b);
                if (op == DATA) { route(source, request, data(b)); return; }
                int error = controlMessage(source, op, b);
                end(b);
                if (request.requestId() != 0)
                    rpc.reply(source, slot, request.routeKey(), new RpcResponse(request.requestId(), error, null,
                            error == 0 && (op == REGISTER || op == VERIFY) ? io.netty.buffer.Unpooled.buffer(8).writeLong(epoch) : null));
            }
        } catch (RuntimeException error) {
            synchronized (lock) { if (routers.containsKey(source)) failed.add(source); }
            throw error;
        } finally { repair(); }
    }
    private int controlMessage(int source, int op, ByteBuf b) {
        if (op == VERIFY) {
            long expectedRouter = b.readLong(), senderEpoch = b.readLong(), senderRevision = b.readLong(); end(b);
            if (expectedRouter != epoch || senderEpoch == 0) return NOT_REGISTERED;
            if (configuredRouters.contains(source)) {
                Peer p = routers.get(source);
                return isRouterReady(source) && p.epoch == senderEpoch && p.revision == senderRevision ? 0 : NOT_REGISTERED;
            }
            NodeRegistration n = local.nodes.get(source);
            if (senderRevision != 0) throw new IllegalArgumentException("Service revision must be zero");
            return n != null && n.nodeEpoch() == senderEpoch ? 0 : NOT_REGISTERED;
        }
        if (op == DATA_ERROR) {
            Peer p = routers.get(source);
            long routerEpoch = b.readLong(); int target = b.readInt(); long targetEpoch = b.readLong();
            int id = b.readInt(), error = b.readInt(); end(b);
            if (p == null || p.table == null || p.epoch != routerEpoch || rpc.peerSlotCount(source) != 1 || id == 0 || error <= 0)
                throw new IllegalArgumentException("Invalid routed error");
            NodeRegistration n = local.nodes.get(target);
            if (n != null && n.nodeEpoch() == targetEpoch) forwardOwned(target, new RpcResponse(id, error, null, null));
            return 0;
        }
        if (op == HELLO) {
            long remoteEpoch = b.readLong(); end(b);
            if (remoteEpoch == 0 || !configuredRouters.contains(source) || rpc.peerSlotCount(source) != 1
                    || local.nodes.containsKey(source)) throw new IllegalArgumentException("Invalid Router identity");
            Peer p = routers.get(source);
            if (p != null && p.helloReceived && p.synchronizedOut) {
                release(p);
                Peer replacement = new Peer(remoteEpoch);
                replacement.table = p.table;
                p = replacement;
            }
            if (p == null) p = new Peer(remoteEpoch);
            p.staging = null; p.synchronizedIn = false;
            p.epoch = remoteEpoch; p.helloReceived = true;
            routers.put(source, p);
            if (!p.sent) snapshot(source, p);
            return 0;
        }
        if (op >= BEGIN && op <= UNBIND) {
            Peer p = routers.get(source);
            if (p == null || rpc.peerSlotCount(source) != 1 || p.epoch == 0 || p.epoch != b.readLong()) throw new IllegalArgumentException("Router epoch or Slot mismatch");
            switch (op) {
                case BEGIN -> {
                    if (p.staging != null) throw new IllegalArgumentException("Repeated snapshot begin");
                    p.nodeCount = b.readInt(); p.bindingCount = b.readInt(); p.stagingRevision = b.readLong(); end(b);
                    if (p.nodeCount < 0 || p.nodeCount > RouterTable.MAX_NODES || p.bindingCount < 0 || p.bindingCount > RouterTable.MAX_BINDINGS)
                        throw new IllegalArgumentException("Snapshot capacity exceeded");
                    p.staging = new RouterTable();
                }
                case NODE -> {
                    if (p.staging == null) throw new IllegalArgumentException("Node outside snapshot");
                    int count = b.readUnsignedShort();
                    if (count < 1 || count > 256 || b.readableBytes() != count * 16) throw new IllegalArgumentException("Invalid Node chunk");
                    for (int i = 0; i < count; i++) p.staging.add(node(b));
                    if (p.staging.nodes.size() > p.nodeCount) throw new IllegalArgumentException("Excess snapshot Nodes");
                }
                case ENTRY -> {
                    if (p.staging == null) throw new IllegalArgumentException("Binding outside snapshot");
                    int count = b.readUnsignedShort();
                    if (count < 1 || count > 256 || b.readableBytes() != count * 24) throw new IllegalArgumentException("Invalid Binding chunk");
                    for (int i = 0; i < count; i++) p.staging.bind(key(b), owner(b));
                    if (p.staging.bindings.size() > p.bindingCount) throw new IllegalArgumentException("Excess snapshot bindings");
                }
                case END -> {
                    end(b);
                    if (p.staging == null || p.staging.nodes.size() != p.nodeCount || p.staging.bindings.size() != p.bindingCount)
                        throw new IllegalArgumentException("Incomplete snapshot");
                    p.table = p.staging; p.staging = null; p.revision = p.stagingRevision; p.synchronizedIn = true; checkConflicts(p.table);
                }
                default -> {
                    if (p.table == null || p.staging != null) throw new IllegalArgumentException("Delta before snapshot end");
                    long nextRevision = b.readLong();
                    if (nextRevision != p.revision + 1) throw new IllegalArgumentException("Router revision gap");
                    if (op == ADD) { NodeRegistration n = node(b); end(b); p.table.add(n); }
                    else if (op == REMOVE) { int id = b.readInt(); long e = b.readLong(); end(b); p.table.remove(id, e); }
                    else {
                        BindingKey key = key(b); RouteBinding owner = owner(b); end(b);
                        if (op == BIND) { p.table.bind(key, owner); checkConflict(key); }
                        else p.table.unbind(key, owner);
                    }
                    p.revision = nextRevision;
                }
            }
            return 0;
        }
        if (configuredRouters.contains(source)) throw new IllegalArgumentException("Router cannot register as service Node");
        if (op == REGISTER) {
            NodeRegistration n = node(b); end(b);
            if (n.nodeId() != source) throw new IllegalArgumentException("Registration source mismatch");
            if (removedNodes.contains(new RemovedNode(source, n.nodeEpoch()))) return NOT_REGISTERED;
            NodeRegistration old = local.nodes.get(source);
            if (old != null && !old.equals(n)) return BINDING_CONFLICT;
            local.add(n); publish(ADD, out -> node(out, n)); return 0;
        }
        long nodeEpoch = b.readLong();
        NodeRegistration n = local.nodes.get(source);
        if (op == DETACH) {
            end(b);
            if (n == null || n.nodeEpoch() != nodeEpoch) return NOT_REGISTERED;
            local.remove(source, nodeEpoch); publish(REMOVE, out -> out.writeInt(source).writeLong(nodeEpoch)); return 0;
        }
        if (op != CLIENT_BIND && op != CLIENT_UNBIND) throw new IllegalArgumentException("Unknown Router operation");
        int service = b.readInt(); long value = b.readLong(); end(b);
        if (n == null || n.nodeEpoch() != nodeEpoch) return NOT_REGISTERED;
        if (service != n.serviceId()) return INVALID_SERVICE;
        BindingKey key = new BindingKey(service, value); RouteBinding owner = new RouteBinding(source, nodeEpoch);
        if (op == CLIENT_BIND) {
            RouteBinding current = resolveKey(key);
            if (current != null && !current.equals(owner)) { checkConflict(key); return BINDING_CONFLICT; }
            local.bind(key, owner); publish(BIND, out -> binding(out, key, owner));
        } else {
            RouteBinding current = local.bindings.get(key);
            if (current != null && !current.equals(owner)) return BINDING_CONFLICT;
            local.unbind(key, owner); publish(UNBIND, out -> binding(out, key, owner));
        }
        return 0;
    }
    private List<RouterTable> tables() {
        List<RouterTable> result = new ArrayList<>(); result.add(local);
        for (var e : routers.entrySet()) if (e.getValue().table != null && !failed.contains(e.getKey())) result.add(e.getValue().table);
        return result;
    }
    private Location locate(int id) {
        Location best = local.nodes.containsKey(id) ? new Location(rpc.nodeId(), local.nodes.get(id)) : null;
        for (var e : routers.entrySet()) {
            Peer p = e.getValue(); if (p.table == null || failed.contains(e.getKey())) continue;
            NodeRegistration n = p.table.nodes.get(id); if (n == null) continue;
            if (best == null || Long.compareUnsigned(n.nodeEpoch(), best.node.nodeEpoch()) > 0
                    || (n.nodeEpoch() == best.node.nodeEpoch() && Integer.compareUnsigned(e.getKey(), best.router) < 0))
                best = new Location(e.getKey(), n);
        }
        return best;
    }
    private RouteBinding resolveKey(BindingKey key) {
        RouteBinding best = null;
        for (RouterTable table : tables()) {
            RouteBinding owner = table.bindings.get(key); if (owner == null) continue;
            Location location = locate(owner.nodeId());
            if (location == null || location.node.nodeEpoch() != owner.nodeEpoch() || location.node.serviceId() != key.serviceId()) continue;
            if (best == null || Integer.compareUnsigned(owner.nodeId(), best.nodeId()) < 0
                    || (owner.nodeId() == best.nodeId() && Long.compareUnsigned(owner.nodeEpoch(), best.nodeEpoch()) > 0)) best = owner;
        }
        return best;
    }
    private void checkConflict(BindingKey key) {
        Set<RouteBinding> owners = new HashSet<>();
        for (RouterTable t : tables()) if (t.bindings.containsKey(key)) owners.add(t.bindings.get(key));
        if (owners.size() > 1) LOG.log(System.Logger.Level.ERROR, "Conflicting Router binding: {0}, owners={1}", key, owners);
    }
    private void checkConflicts(RouterTable table) { for (BindingKey key : table.bindings.keySet()) checkConflict(key); }
    private void route(int peerId, RpcRequest outer, Envelope e) {
        boolean remote = routers.containsKey(peerId);
        RouterTable sourceTable = remote ? routers.get(peerId).table : local;
        NodeRegistration source = sourceTable == null ? null : sourceTable.nodes.get(e.source());
        if ((!remote && (source == null || source.nodeEpoch() != e.sourceEpoch() || peerId != e.source()))
                || (remote && (sourceTable == null || failed.contains(peerId)))
                || (remote ? e.hops() != 1 || outer.requestId() != 0 : e.hops() != 0))
            throw new IllegalArgumentException("Unregistered/spoofed routed source");
        // A retained bucket is queryable authority, not permission to forward during repair.
        if (remote && !isRouterReady(peerId)) { fail(e, RpcErrorCodes.UNAVAILABLE); return; }
        if (!remote && e.message() instanceof RpcRequest r) {
            if (r.requestId() != 0) throw new IllegalArgumentException("Client inner requestId must be zero");
            RpcRequest assigned = new RpcRequest(r.command(), outer.requestId(), r.routeKey(), r.businessIdType(), r.businessId(), r.metadata(), r.body());
            e = new Envelope(e.mode(), e.source(), e.sourceEpoch(), e.target(), e.targetEpoch(), e.service(), e.key(), e.hops(), assigned);
        }
        if (e.mode() == BROADCAST) {
            if (((RpcRequest) e.message()).requestId() != 0 || e.service() <= 0) throw new IllegalArgumentException("Broadcast requires notify/service");
            for (NodeRegistration n : local.nodes.values()) if (n.serviceId() == e.service()) {
                Location location = locate(n.nodeId());
                if (location != null && location.router == rpc.nodeId()) deliver(e, location);
            }
            if (!remote) for (var entry : routers.entrySet()) if (entry.getValue().table != null && isRouterReady(entry.getKey())) {
                forwardData(entry.getKey(), copy(e, BROADCAST, 0, 0, 1));
            }
            return;
        }
        Location target;
        if (e.mode() == DYNAMIC) {
            if (remote) throw new IllegalArgumentException("Dynamic routes must be resolved at source Router");
            RouteBinding owner = resolveKey(new BindingKey(e.service(), e.key()));
            if (owner == null) { fail(e, ROUTE_NOT_FOUND); return; }
            target = locate(owner.nodeId());
        } else target = locate(e.target());
        if (target == null || (e.targetEpoch() != 0 && target.node.nodeEpoch() != e.targetEpoch())
                || (remote && target.router != rpc.nodeId())) {
            fail(e, RpcErrorCodes.PEER_NOT_FOUND); return;
        }
        if (target.router == rpc.nodeId()) deliver(e, target);
        else {
            if (!isRouterReady(target.router)) { fail(e, RpcErrorCodes.UNAVAILABLE); return; }
            int mode = e.mode() == RESPONSE ? RESPONSE : DIRECT;
            RpcSendStatus status = forwardData(target.router, copy(e, mode, target.node.nodeId(), target.node.nodeEpoch(), 1));
            if (status != RpcSendStatus.ACCEPTED) fail(e, sendError(status));
        }
    }
    private RpcSendStatus forwardData(int id, Envelope e) {
        try { return forwardOwned(id, data(e)); }
        catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "Routed data send failed", error);
            return RpcSendStatus.UNAVAILABLE;
        }
    }
    private RpcSendStatus forwardOwned(int id, RpcRequest message) {
        // All bodies passed here are private freshly encoded buffers, never borrowed/shared slices.
        try { return rpc.notify(id, message); }
        finally { if (message.body() != null && message.body().refCnt() > 0) message.body().release(); }
    }
    private RpcSendStatus forwardOwned(int id, RpcResponse message) {
        try { return rpc.reply(id, 0, 0, message); }
        finally { if (message.body() != null && message.body().refCnt() > 0) message.body().release(); }
    }
    private void deliver(Envelope e, Location target) {
        RpcSendStatus status;
        if (e.mode() == RESPONSE) {
            RpcResponse response = (RpcResponse) e.message();
            RpcRequest encoded = data(copy(e, RESPONSE, target.node.nodeId(), target.node.nodeEpoch(), 1));
            status = forwardOwned(target.node.nodeId(), new RpcResponse(response.requestId(), 0, null, encoded.body()));
        } else status = forwardData(target.node.nodeId(), copy(e, DIRECT, target.node.nodeId(), target.node.nodeEpoch(), 1));
        if (status != RpcSendStatus.ACCEPTED) fail(e, sendError(status));
    }
    private void fail(Envelope e, int error) {
        if (!(e.message() instanceof RpcRequest r) || r.requestId() == 0) return;
        Location source = locate(e.source());
        if (source == null || source.node.nodeEpoch() != e.sourceEpoch()) return;
        if (source.router == rpc.nodeId()) forwardOwned(e.source(), new RpcResponse(r.requestId(), error, null, null));
        else {
            // Errors have no synchronization ACK dependency or intermediary pending call.
            ByteBuf b = control(DATA_ERROR).writeLong(epoch).writeInt(e.source()).writeLong(e.sourceEpoch()).writeInt(r.requestId()).writeInt(error);
            try { forwardOwned(source.router, packet(b)); }
            catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Routed error send failed", failure);
            }
        }
    }
    private static int sendError(RpcSendStatus status) {
        return status == RpcSendStatus.PEER_NOT_FOUND ? RpcErrorCodes.PEER_NOT_FOUND : RpcErrorCodes.UNAVAILABLE;
    }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        if (!(callback instanceof Control c)) throw new IllegalStateException("Router never starts business calls");
        synchronized (lock) {
            if (closed || routers.get(c.id) != c.peer) return;
            c.peer.sending = false;
            if (response.errorCode() != 0 || (c.operation == VERIFY && (response.body() == null
                    || response.body().readableBytes() != 8 || response.body().getLong(response.body().readerIndex()) != c.peer.epoch))) failed.add(c.id);
            else {
                if (c.operation == END) {
                    c.peer.synchronizedOut = true; c.peer.lastVerification = System.nanoTime();
                    if (c.peer.table != null) checkConflicts(c.peer.table);
                }
                pump(c.id, c.peer);
            }
        }
        repair();
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        if (!(callback instanceof Control c)) return;
        boolean nested = Thread.holdsLock(lock);
        synchronized (lock) { if (routers.get(c.id) == c.peer) failed.add(c.id); }
        if (!nested) repair();
    }
    void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; local.clear(); removedNodes.clear();
            routers.values().forEach(RouterEngine::release); routers.clear(); configuredRouters.clear(); failed.clear();
            retries.shutdownNow();
        }
    }
}
