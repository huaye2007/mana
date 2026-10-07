package cn.managame.router.node;

import cn.managame.rpc.call.*;
import cn.managame.rpc.error.RpcErrorCodes;
import cn.managame.rpc.message.*;
import cn.managame.rpc.node.RpcNode;
import cn.managame.rpc.transport.RpcSendStatus;
import cn.managame.router.call.*;
import cn.managame.router.route.*;
import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.*;
import static cn.managame.router.node.RouterWire.*;

/** Service registration, bindings and addressing on the process's existing RpcNode. */
public final class ServiceRouting implements RpcHandler, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ServiceRouting.class.getName());
    private final Object lock = new Object();
    private volatile RpcNode rpc;
    private final RpcHandler directHandler;
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "service-routing-maintenance"); thread.setDaemon(true); return thread;
    });
    private final RouterHandler handler;
    private final int service;
    private final Set<Long> bindings = new HashSet<>();
    private int router;
    private long epoch, generation;
    private long routerEpoch, lastVerification;
    private boolean retryRegistration, wasConnected;
    static final int MAX_CONTROLS = 8192;
    private boolean registered, registering, closed, detaching, restoring;
    private RpcCallback<Integer> registrationCallback;
    private final ArrayDeque<Control> controls = new ArrayDeque<>();
    private Control active;
    private boolean pumping;
    private int restoreRemaining, restoreError;
    private Iterator<Long> restoringKeys = Collections.emptyIterator();
    private static final class Control implements RpcCallback<Object> {
        final long generation, key;
        final int op;
        final RpcCallback<Integer> callback;
        final boolean restore;
        Control(long generation, int op, long key, RpcCallback<Integer> callback, boolean restore) {
            this.generation = generation; this.op = op; this.key = key; this.callback = callback; this.restore = restore;
        }
        @Override public void onResponse(Object ignored) {}
    }
    private record Business(int command, RpcCallback<?> callback) implements RpcCallback<Object> {
        @Override public void onResponse(Object ignored) {}
    }
    ServiceRouting(int serviceId, RouterHandler handler, RpcHandler directHandler) {
        if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
        service = serviceId; this.handler = Objects.requireNonNull(handler);
        this.directHandler = Objects.requireNonNull(directHandler);
    }
    /** Starts routing maintenance on the existing Node. The application starts and closes RPC. */
    public void start(RpcNode rpcNode) {
        synchronized (lock) {
            if (closed || rpc != null) throw new IllegalStateException("Routing already started or closed");
            rpc = Objects.requireNonNull(rpcNode);
            maintenance.scheduleWithFixedDelay(this::maintain, 100, 100, TimeUnit.MILLISECONDS);
        }
    }
    private void maintain() {
        try {
            int id; boolean connected, lost;
            synchronized (lock) {
                if (closed || router == 0) return;
                id = router; connected = rpc.isPeerConnected(id); lost = wasConnected && !connected;
                wasConnected = connected;
            }
            if (lost) interrupt(id, RpcErrorCodes.UNAVAILABLE, false, true);
            if (!connected) return;
            synchronized (lock) {
                if (closed || router != id || detaching || active != null || !controls.isEmpty()) return;
                if (registered && !restoring && System.nanoTime() - lastVerification >= TimeUnit.SECONDS.toNanos(1)) {
                    lastVerification = System.nanoTime();
                    controls.addLast(new Control(generation, VERIFY, 0, null, false));
                }
            }
            onConnected(id); pump();
        } catch (RuntimeException error) { LOG.log(System.Logger.Level.WARNING, "Routing maintenance failed", error); }
    }
    public boolean isRegistered() { synchronized (lock) { return registered && !restoring && !closed && !detaching && rpc != null && rpc.isPeerConnected(router); } }
    public Set<Long> bindings() { synchronized (lock) { return Set.copyOf(bindings); } }
    public void register(int routerId, long nodeEpoch, RpcCallback<Integer> callback) {
        if (rpc == null) throw new IllegalStateException("Start routing before registration");
        if (routerId == 0 || routerId == rpc.nodeId() || nodeEpoch == 0)
            throw new IllegalArgumentException("invalid registration");
        Objects.requireNonNull(callback);
        synchronized (lock) {
            if (closed || (router != 0 && (router != routerId || epoch != nodeEpoch || registered || registering || restoring || registrationCallback != null)))
                throw new IllegalStateException("Node closed or registration already active");
            if (router == 0 && epoch != 0 && nodeEpoch == epoch) throw new IllegalArgumentException("New registration must use a new nodeEpoch");
            router = routerId; epoch = nodeEpoch; generation++; registered = false; detaching = false;
            retryRegistration = true; wasConnected = rpc.isPeerConnected(routerId);
            registrationCallback = callback;
        }
        if (rpc.isPeerConnected(routerId)) onConnected(routerId);
    }
    private void onConnected(int id) {
        if (!rpc.isPeerConnected(id)) return;
        synchronized (lock) {
            if (id != router || closed || detaching || registered || registering || !retryRegistration
                    || active != null || !controls.isEmpty()) return;
            registering = true; restoring = true; restoreRemaining = 0; restoreError = 0;
            controls.addLast(new Control(generation, REGISTER, 0, null, false));
        }
        pump();
    }
    public void bind(long key, RpcCallback<Integer> callback) { binding(CLIENT_BIND, key, callback); }
    public void unbind(long key, RpcCallback<Integer> callback) { binding(CLIENT_UNBIND, key, callback); }
    private void binding(int op, long key, RpcCallback<Integer> callback) {
        Objects.requireNonNull(callback);
        synchronized (lock) { ready(); checkControlCapacity(); controls.addLast(new Control(generation, op, key, callback, false)); }
        pump();
    }
    public void unregister(RpcCallback<Integer> callback) {
        Objects.requireNonNull(callback);
        synchronized (lock) {
            if (closed || rpc == null || router == 0 || registering || restoring || detaching || registrationCallback != null)
                throw new IllegalStateException("No settled Router selection");
            if (!rpc.isPeerConnected(router)) throw new IllegalStateException("Use unregisterAfterLoss without transport");
            checkControlCapacity(); detaching = true;
            controls.addLast(new Control(generation, DETACH, 0, callback, false));
        }
        pump();
    }
    public void unregisterAfterLoss() {
        int old;
        synchronized (lock) {
            if (router == 0 || rpc.isPeerConnected(router))
                throw new IllegalStateException("Old Router must be disconnected");
            old = router;
        }
        interrupt(old, RpcErrorCodes.PEER_REMOVED, true);
    }
    private void pump() {
        synchronized (lock) { if (pumping) return; pumping = true; }
        while (true) {
            Control c;
            int target;
            ByteBuf body;
            synchronized (lock) {
                if (closed || active != null) { pumping = false; return; }
                if (controls.isEmpty() && restoringKeys.hasNext())
                    controls.addLast(new Control(generation, CLIENT_BIND, restoringKeys.next(), null, true));
                if (controls.isEmpty()) { pumping = false; return; }
                c = controls.removeFirst(); active = c; target = router;
                body = control(c.op);
                if (c.op == REGISTER) node(body, new NodeRegistration(rpc.nodeId(), epoch, service));
                else if (c.op == VERIFY) body.writeLong(routerEpoch).writeLong(epoch).writeLong(0);
                else {
                    body.writeLong(epoch);
                    if (c.op != DETACH) body.writeInt(service).writeLong(c.key);
                }
            }
            try { rpc.call(target, packet(body), c); }
            catch (RuntimeException error) { complete(c, RpcErrorCodes.NODE_CLOSED); }
            catch (Error error) { synchronized (lock) { pumping = false; } throw error; }
            finally { if (body.refCnt() > 0) body.release(); }
        }
    }
    private void complete(Control c, int error) {
        if (c.op == VERIFY) {
            int id;
            synchronized (lock) {
                if (active != c || c.generation != generation) return;
                active = null; id = router;
            }
            if (error != 0) interrupt(id, error, false, true, c.generation);
            pump(); return;
        }
        RpcCallback<Integer> registration = null;
        boolean registrationDone = false;
        int registrationId = 0, registrationError = error, controlRouter;
        synchronized (lock) {
            if (active != c || c.generation != generation) return;
            active = null; controlRouter = router;
            if (c.op == REGISTER) {
                registering = false; registered = error == 0 && !closed;
                retryRegistration = error == 0 || transientError(error);
                lastVerification = System.nanoTime();
                if (registered) {
                    restoreRemaining = bindings.size();
                    restoringKeys = bindings.iterator();
                }
                registrationDone = !registered || restoreRemaining == 0;
            } else if (c.restore) {
                if (error != 0 && restoreError == 0) restoreError = error;
                if (error != 0) { restoreRemaining = 1; restoringKeys = Collections.emptyIterator(); }
                registrationDone = --restoreRemaining == 0;
                registrationError = restoreError;
                if (restoreError != 0) { registered = false; retryRegistration = transientError(restoreError)
                        || restoreError == cn.managame.router.error.RouterErrorCodes.NOT_REGISTERED; }
            } else if (c.op == DETACH) {
                detaching = false;
                if (error == 0) { router = 0; registered = false; generation++; bindings.clear(); }
            } else if (error == 0) {
                if (c.op == CLIENT_BIND) bindings.add(c.key); else bindings.remove(c.key);
            }
            if (registrationDone) {
                restoring = false; registrationId = router;
                registration = registrationCallback; registrationCallback = null;
            }
        }
        try {
            deliver(c.callback, error);
            if (!c.restore && c.op != REGISTER && error == cn.managame.router.error.RouterErrorCodes.NOT_REGISTERED)
                interrupt(controlRouter, error, false, true, c.generation);
            if (registrationDone) {
                deliver(registration, registrationError);
                registrationEvent(registrationId, registrationError);
            }
        } finally { pump(); }
    }
    private void interrupt(int id, int error, boolean clear) {
        interrupt(id, error, clear, false);
    }
    private void interrupt(int id, int error, boolean clear, boolean preserveRegistration) {
        interrupt(id, error, clear, preserveRegistration, null);
    }
    private void interrupt(int id, int error, boolean clear, boolean preserveRegistration, Long expectedGeneration) {
        List<Control> abandoned;
        RpcCallback<Integer> registration;
        boolean report;
        synchronized (lock) {
            if (id != router || router == 0 || (expectedGeneration != null && generation != expectedGeneration)) return;
            if (clear && error == RpcErrorCodes.PEER_REMOVED && rpc.isPeerConnected(id))
                throw new IllegalStateException("Old Router recovered before unregister");
            generation++; report = registering || restoring;
            registered = false; registering = false; restoring = false; detaching = false;
            retryRegistration = !clear && !closed;
            restoringKeys = Collections.emptyIterator();
            abandoned = new ArrayList<>(controls); controls.clear();
            if (active != null) { abandoned.addFirst(active); active = null; }
            registration = preserveRegistration ? null : registrationCallback;
            if (!preserveRegistration) registrationCallback = null;
            if (clear) router = 0;
        }
        for (Control c : abandoned) deliver(c.callback, error);
        deliver(registration, error);
        if (report) registrationEvent(id, error);
    }
    private static void deliver(RpcCallback<Integer> callback, int error) {
        if (callback == null) return;
        try { callback.onResponse(error); }
        catch (RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Router control callback failed", failure); }
    }
    private void registrationEvent(int id, int error) {
        try { handler.onRegistration(id, error); }
        catch (RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Router registration handler failed", failure); }
    }
    private void checkControlCapacity() {
        if (controls.size() >= MAX_CONTROLS) throw new RejectedExecutionException("Routing control queue is full");
    }
    private static boolean transientError(int error) {
        return error == RpcErrorCodes.UNAVAILABLE || error == RpcErrorCodes.PEER_NOT_FOUND
                || error == RpcErrorCodes.PEER_REMOVED || error == RpcErrorCodes.TIMEOUT;
    }

    public RpcSendStatus notifyNode(int nodeId, RpcRequest request) { return notify(DIRECT, nodeId, 0, 0, request); }
    public RpcSendStatus notifyBinding(int serviceId, long key, RpcRequest request) { return notify(DYNAMIC, 0, serviceId, key, request); }
    public RpcSendStatus broadcast(int serviceId, RpcRequest request) { return notify(BROADCAST, 0, serviceId, 0, request); }
    private RpcSendStatus notify(int mode, int target, int serviceId, long key, RpcRequest request) {
        Envelope envelope; int next;
        synchronized (lock) {
            validate(mode, target, serviceId, request); next = router;
            envelope = new Envelope(mode, rpc.nodeId(), epoch, target, 0, serviceId, key, 0, request);
        }
        RpcRequest out = data(envelope);
        try { return rpc.notify(next, out); }
        finally { if (out.body().refCnt() > 0) out.body().release(); }
    }
    public <T> void callNode(int nodeId, RpcRequest request, RpcCallback<T> callback) {
        call(DIRECT, nodeId, 0, 0, request, 0, callback);
    }
    public <T> void callBinding(int serviceId, long key, RpcRequest request, RpcCallback<T> callback) {
        call(DYNAMIC, 0, serviceId, key, request, 0, callback);
    }
    public <T> void callNode(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        call(DIRECT, nodeId, 0, 0, request, timeoutMillis, callback);
    }
    public <T> void callBinding(int serviceId, long key, RpcRequest request, long timeoutMillis, RpcCallback<T> callback) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        call(DYNAMIC, 0, serviceId, key, request, timeoutMillis, callback);
    }
    private <T> void call(int mode, int target, int serviceId, long key, RpcRequest request, long timeout, RpcCallback<T> callback) {
        Objects.requireNonNull(callback);
        if (timeout < 0 || timeout > Long.MAX_VALUE / 1_000_000) throw new IllegalArgumentException("invalid timeout");
        Envelope envelope; int next;
        synchronized (lock) {
            validate(mode, target, serviceId, request); next = router;
            envelope = new Envelope(mode, rpc.nodeId(), epoch, target, 0, serviceId, key, 0, request);
        }
        RpcRequest out = data(envelope);
        try {
            Business completion = new Business(request.command(), callback);
            if (timeout == 0) rpc.call(next, out, completion);
            else rpc.call(next, out, timeout, completion);
        } finally { if (out.body().refCnt() > 0) out.body().release(); }
    }
    /** Reply targets the original physical Node/epoch, even after its dynamic binding changed. */
    public RpcSendStatus reply(RoutedRequest request, RpcResponse response) {
        Objects.requireNonNull(request); Objects.requireNonNull(response);
        if (request.request().requestId() == 0 || request.request().requestId() != response.requestId())
            throw new IllegalArgumentException("response ID must match a routed call");
        Envelope envelope; int next;
        synchronized (lock) {
            attached(); next = router;
            envelope = new Envelope(RESPONSE, rpc.nodeId(), epoch, request.sourceNodeId(),
                    request.sourceNodeEpoch(), 0, 0, 0, response);
        }
        RpcRequest out = data(envelope);
        try { return rpc.notify(next, out); }
        finally { if (out.body().refCnt() > 0) out.body().release(); }
    }
    private void validate(int mode, int target, int serviceId, RpcRequest request) {
        Objects.requireNonNull(request);
        if (request.requestId() != 0 || (mode == DIRECT ? target == 0 : serviceId <= 0))
            throw new IllegalArgumentException("invalid routed request/target");
        ready();
    }
    private void ready() {
        attached();
        if (restoring) throw new IllegalStateException("Bindings are still being restored");
    }
    private void attached() {
        if (closed || rpc == null || !registered || detaching || !rpc.isPeerConnected(router)) throw new IllegalStateException("Node has no active Router registration");
    }
    @Override public void onRequest(int source, int slot, RpcRequest request) {
        if (request.command() != COMMAND) { directHandler.onRequest(source, slot, request); return; }
        routedRequest(source, slot, request);
    }
    private void routedRequest(int source, int slot, RpcRequest request) {
        if (request.command() != COMMAND) throw new IllegalArgumentException("Non-Router request reached routing extension");
        Envelope e;
        synchronized (lock) {
            if (closed || source != router || !registered || request.requestId() != 0) throw new IllegalArgumentException("Invalid Router delivery");
            ByteBuf b = request.body().duplicate();
            if (operation(b) != DATA) throw new IllegalArgumentException("Client accepts only Router data");
            e = data(b);
            if (e.mode() != DIRECT || e.target() != rpc.nodeId() || e.targetEpoch() != epoch || e.hops() != 1)
                throw new IllegalArgumentException("Delivery attachment mismatch");
        }
        RoutedRequest incoming = new RoutedRequest(e.source(), e.sourceEpoch(), (RpcRequest) e.message());
        try { handler.onRoutedRequest(incoming); }
        catch (RuntimeException error) {
            if (incoming.request().requestId() != 0) reply(incoming, new RpcResponse(incoming.request().requestId(), RpcErrorCodes.HANDLER_ERROR, null, null));
            throw error;
        }
    }
    @Override public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
        if (callback instanceof Control c) {
            int error = response.errorCode();
            if (c.op == DETACH && error == cn.managame.router.error.RouterErrorCodes.NOT_REGISTERED) error = 0;
            synchronized (lock) {
                if (closed || active != c || c.generation != generation) return;
                if (error == 0 && (c.op == REGISTER || c.op == VERIFY)) {
                    ByteBuf body = response.body();
                    if (body == null || body.readableBytes() != 8 || body.getLong(body.readerIndex()) == 0) error = RpcErrorCodes.PROTOCOL_ERROR;
                    else if (c.op == VERIFY && body.getLong(body.readerIndex()) != routerEpoch) error = cn.managame.router.error.RouterErrorCodes.NOT_REGISTERED;
                    else routerEpoch = body.getLong(body.readerIndex());
                }
            }
            complete(c, error);
        } else if (callback instanceof Business c) {
            if (response.errorCode() != 0) { handler.onResponse(source, c.command, response, c.callback); return; }
            Envelope e;
            try {
                ByteBuf b = response.body().duplicate();
                if (operation(b) != DATA) throw new IllegalArgumentException("Invalid routed response");
                e = data(b);
                synchronized (lock) {
                    if (source != router || e.mode() != RESPONSE || e.target() != rpc.nodeId() || e.targetEpoch() != epoch
                            || ((RpcResponse) e.message()).requestId() != response.requestId()) throw new IllegalArgumentException("Response attachment mismatch");
                }
            } catch (RuntimeException malformed) {
                handler.onFail(source, c.command, RpcErrorCodes.PROTOCOL_ERROR, c.callback);
                return;
            }
            handler.onResponse(e.source(), c.command, (RpcResponse) e.message(), c.callback);
        } else directHandler.onResponse(source, command, response, callback);
    }
    @Override public void onFail(int target, int command, int error, RpcCallback<?> callback) {
        if (callback instanceof Control c) complete(c, error);
        else if (callback instanceof Business c) handler.onFail(target, c.command, error, c.callback);
        else directHandler.onFail(target, command, error, callback);
    }
    @Override public void close() {
        int old;
        synchronized (lock) { if (closed) return; closed = true; old = router; restoringKeys = Collections.emptyIterator(); bindings.clear(); maintenance.shutdownNow(); }
        interrupt(old, RpcErrorCodes.NODE_CLOSED, true);
    }
}
