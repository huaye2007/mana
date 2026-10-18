package cn.managame.runtime.internal;

import static cn.managame.core.FrameworkErrorCodes.HANDLER_CONTEXT_MISMATCH;
import static cn.managame.core.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.core.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static cn.managame.core.FrameworkErrorCodes.ROUTE_CALLBACK_DISPATCH_FAILED;
import static cn.managame.core.FrameworkErrorCodes.ROUTE_CALL_EXECUTION_ERROR;
import static cn.managame.core.FrameworkErrorCodes.ROUTE_DOMAIN_MISMATCH;
import static cn.managame.core.FrameworkErrorCodes.ROUTE_EXECUTOR_CLOSED;
import static cn.managame.core.FrameworkErrorCodes.ROUTE_EXECUTOR_OVERLOADED;
import static cn.managame.core.FrameworkErrorCodes.RUNTIME_CLOSED;
import static cn.managame.core.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;

import cn.managame.core.FrameworkErrorCodes;
import cn.managame.core.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.WriteStatus;
import io.netty.util.ReferenceCountUtil;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.DefaultClientHandlerContext;
import cn.managame.runtime.context.DefaultEventContext;
import cn.managame.runtime.context.DefaultInvocationContext;
import cn.managame.runtime.context.DefaultRouteCallContext;
import cn.managame.runtime.context.DefaultRpcHandlerContext;
import cn.managame.runtime.context.DefaultTimerContext;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.InvocationContext;
import cn.managame.runtime.diagnostic.DomainStats;
import cn.managame.runtime.diagnostic.RuntimeStats;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutorStats;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.handler.HandlerContextFactory;
import cn.managame.runtime.handler.HandlerObserver;
import cn.managame.runtime.http.HttpDispatcher;
import cn.managame.runtime.internal.RuntimeBindings.Compiled;
import cn.managame.runtime.internal.RuntimeBindings.EventSubscribers;
import cn.managame.runtime.internal.RuntimeBindings.HandlerBinding;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.CapturedRouteCallback;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteKeyBinding;
import cn.managame.runtime.route.RouteKeyRegistry;
import cn.managame.runtime.timer.CronScheduler;
import cn.managame.runtime.timer.RuntimeTimer;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

final class DefaultGameRuntime implements GameRuntime {
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime");
    private final Map<Integer, RouteExecutor> routes;
    private final ProtocolRegistry protocols;
    private final Map<Class<?>, RouteKeyBinding<?>> keys;
    private final Map<Class<?>, HandlerBinding> handlers;
    private final HandlerContextFactory handlerContexts;
    private final ClientResultCodec clientResults;
    private final HandlerObserver observer;
    private final Map<Class<?>, EventSubscribers> events;
    private final RuntimeErrorHandler errors;
    private final boolean executionTiming;
    private final RuntimeTimers timers;
    private final DefaultCronScheduler crons;
    private final HttpDispatcher http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AdmissionGate admission = new AdmissionGate();
    /** Captured continuations that drain still waits for; see RuntimeStats.continuations. */
    private final Set<Captured<?>> outstanding = ConcurrentHashMap.newKeySet();
    /** One counter set per registered Domain; the map is built at construction and never mutated. */
    private final Map<Integer, DomainCounters> perDomain;
    // Striped counters: every Route task updates them, so a single atomic word would be contended by all workers.
    private final LongAdder queued = new LongAdder(), running = new LongAdder(), completed = new LongAdder(),
            rejected = new LongAdder(), errorCount = new LongAdder(), executionNanos = new LongAdder();

    DefaultGameRuntime(Compiled compiled) {
        routes = compiled.routes();
        var counters = new HashMap<Integer, DomainCounters>();
        for (Integer domain : routes.keySet()) counters.put(domain, new DomainCounters());
        perDomain = Map.copyOf(counters);
        protocols = compiled.protocols();
        keys = compiled.routeKeys();
        handlers = compiled.handlers();
        handlerContexts = compiled.handlerContexts();
        clientResults = compiled.clientResults();
        observer = compiled.handlerObserver();
        events = compiled.events();
        errors = compiled.errors();
        executionTiming = compiled.executionTiming();
        http = new RuntimeHttp(compiled.http(), compiled.httpContexts(), compiled.httpResults(),
                () -> closed.get() || (!admission.accepting() && !RuntimeContexts.ownedBy(this)),
                (context, action) -> submit(context, action, false),
                (context, cause) -> report(RUNTIME_EXECUTION_ERROR, context, cause));
        timers = new RuntimeTimers(this::validate, (context, action) -> submit(context, action, false),
                error -> report(error.errorCode(), error.context(), error.cause()));
        crons = new DefaultCronScheduler(compiled.crons(), timers,
                (binding, error) -> report(RUNTIME_EXECUTION_ERROR, new DefaultTimerContext(binding.domain(), binding.key()), error));
        try { crons.start(); }
        catch (RuntimeException | Error e) { crons.close(); timers.close(); throw e; }
    }

    public ProtocolRegistry protocols() { return protocols; }
    public RouteKeyRegistry routeKeys() { return message -> extractKey(Objects.requireNonNull(message)); }
    public EventBus eventBus() { return this::publish; }
    public RuntimeTimer timer() { return timers; }
    public CronScheduler cron() { return crons; }
    public HttpDispatcher http() { return http; }

    // ---------------------------------------------------------------- admission

    private void validate(int domain, long key) {
        // The Domain is known here, so a closure refusal is attributed to it; an unknown one counts only
        // for the Runtime, as do the entries that reject before any Handler has named a Domain.
        requireAdmission(perDomain.get(domain));
        if (!routes.containsKey(domain)) throw failure(ROUTE_DOMAIN_MISMATCH);
        if (key == 0) throw failure(INVALID_ROUTE_KEY);
    }

    private static RuntimeDispatchException failure(int code) {
        return new RuntimeDispatchException(code, "Runtime dispatch rejected: " + FrameworkErrorCodes.describe(code));
    }

    private void requireAdmission() { requireAdmission(null); }

    private void requireAdmission(DomainCounters domain) {
        if (closed.get() || (!admission.accepting() && !RuntimeContexts.ownedBy(this))) {
            reject(domain);
            throw failure(RUNTIME_CLOSED);
        }
    }

    /** Enters the target Route, inline when already on it; returns 0 or the rejection code. */
    private int submit(Context context, Runnable action, boolean allowClosed) {
        if (closed.get() && !allowClosed) return RUNTIME_CLOSED;
        RouteExecutor executor = routes.get(context.routeDomain());
        if (executor == null) return ROUTE_DOMAIN_MISMATCH;
        if (context.routeKey() == 0) return INVALID_ROUTE_KEY;
        boolean owned = RuntimeContexts.ownedBy(this);
        // The executor lookup above proves the Domain is registered, so its counters exist.
        DomainCounters domain = perDomain.get(context.routeDomain());
        if (!admission.acquire(allowClosed || owned)) { reject(domain); return RUNTIME_CLOSED; }
        queued.increment(); domain.queued().increment();
        Runnable bound = () -> {
            queued.decrement(); domain.queued().decrement();
            running.increment(); domain.running().increment();
            long started = executionTiming ? System.nanoTime() : 0;
            try { RuntimeContexts.run(this, context, action); }
            finally {
                if (executionTiming) executionNanos.add(System.nanoTime() - started);
                running.decrement(); domain.running().decrement();
                completed.increment(); domain.completed().increment();
                admission.release();
            }
        };
        if (owned) {
            Context current = Contexts.current();
            if (current.routeDomain() == context.routeDomain() && current.routeKey() == context.routeKey()) {
                bound.run();
                return 0;
            }
        }
        try {
            int result = switch (Objects.requireNonNull(executor.tryExecute(context.routeDomain(), context.routeKey(), bound))) {
                case ACCEPTED -> 0;
                case OVERLOADED -> ROUTE_EXECUTOR_OVERLOADED;
                case CLOSED -> ROUTE_EXECUTOR_CLOSED;
            };
            if (result != 0) { queued.decrement(); domain.queued().decrement(); admission.release(); reject(domain); }
            return result;
        } catch (Throwable e) {
            queued.decrement(); domain.queued().decrement(); admission.release(); reject(domain);
            report(RUNTIME_EXECUTION_ERROR, context, e);
            return RUNTIME_EXECUTION_ERROR;
        }
    }

    /** One refusal, counted for the Runtime and for the Domain it was refused on. */
    private void reject(DomainCounters domain) {
        rejected.increment();
        if (domain != null) domain.rejected().increment();
    }

    /** Per-Domain counters, created at build for every registered Domain and never replaced. */
    private record DomainCounters(LongAdder queued, LongAdder running, LongAdder completed, LongAdder rejected) {
        DomainCounters() { this(new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()); }
    }

    // ---------------------------------------------------------------- dispatch entries

    private HandlerBinding handlerFor(Object message) {
        HandlerBinding handler = handlers.get(Objects.requireNonNull(message, "message").getClass());
        if (handler == null) throw failure(HANDLER_NOT_FOUND);
        return handler;
    }

    private long extractKey(Object message) {
        RouteKeyBinding<?> binding = keys.get(message.getClass());
        return binding == null ? 0 : extractKey(binding, message);
    }

    private static <T> long extractKey(RouteKeyBinding<T> binding, Object message) {
        return binding.extractor().extract(binding.messageType().cast(message));
    }

    @Override public void dispatch(Connection connection, int command, int requestId, Metadata metadata, Object message) {
        Objects.requireNonNull(metadata);
        requireAdmission();
        HandlerBinding handler = handlerFor(message);
        if (handlerContexts == null) {
            dispatch(new DefaultClientHandlerContext(handler.domain(), extractKey(message), 0, 0, metadata, message,
                    connection, command, requestId));
            return;
        }
        ClientHandlerContext context = Objects.requireNonNull(
                handlerContexts.create(handler.domain(), command, requestId, connection, metadata, message),
                "HandlerContextFactory returned null");
        if (context.routeDomain() != handler.domain()) throw failure(ROUTE_DOMAIN_MISMATCH);
        if (context.message() != message || context.connection() != connection || context.metadata() != metadata
                || context.command() != command || context.requestId() != requestId)
            throw failure(HANDLER_CONTEXT_MISMATCH);
        dispatch(context);
    }

    @Override public void dispatch(Connection connection, long routeKey, int businessIdType, long businessId,
                                   Metadata metadata, Object message) {
        Objects.requireNonNull(metadata);
        requireAdmission();
        HandlerBinding handler = handlerFor(message);
        dispatch(new DefaultClientHandlerContext(handler.domain(), routeKey, businessIdType, businessId,
                metadata, message, connection));
    }

    @Override public void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey,
                                      int businessIdType, long businessId, Metadata metadata, Object message) {
        requireAdmission();
        HandlerBinding handler = handlerFor(message);
        dispatch(new DefaultRpcHandlerContext(handler.domain(), routeKey, businessIdType, businessId, metadata,
                message, sourceNodeId, sourceSlotId, command, requestId));
    }

    @Override public void dispatch(HandlerContext context) {
        Objects.requireNonNull(context);
        validate(context.routeDomain(), context.routeKey());
        Object message = Objects.requireNonNull(context.message());
        HandlerBinding handler = handlerFor(message);
        if (handler.domain() != context.routeDomain()) throw failure(ROUTE_DOMAIN_MISMATCH);
        if (handler.contextType() != null && !handler.contextType().isInstance(context)) throw failure(HANDLER_CONTEXT_MISMATCH);
        Object[] arguments = handler.resolve(context);
        // The context type check above guarantees a client context whenever the Handler returns a response.
        ClientHandlerContext client = handler.returnsResult() ? (ClientHandlerContext) context : null;
        int error = submit(context, () -> {
            // The method alone is timed; the response that may follow it is delivery, not business.
            long started = observer == null ? 0L : started(context);
            Object result;
            try {
                result = handler.invoke(context, message, arguments);
            } catch (Throwable e) {
                if (observer != null) failed(context, started, e);
                report(RUNTIME_EXECUTION_ERROR, context, e);
                if (client != null) send(client, null, RUNTIME_EXECUTION_ERROR, false);
                return;
            }
            if (observer != null) finished(context, started);
            if (client != null) send(client, result, 0, true);
        }, false);
        if (error != 0) throw failure(error);
    }

    // ------------------------------------------------------- Handler observation (never intervenes)

    private long started(HandlerContext context) {
        try { observer.onStart(context); }
        catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
        return System.nanoTime();
    }

    private void finished(HandlerContext context, long startedAt) {
        try { observer.onFinish(context, System.nanoTime() - startedAt); }
        catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
    }

    private void failed(HandlerContext context, long startedAt, Throwable cause) {
        try { observer.onError(context, System.nanoTime() - startedAt, cause); }
        catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
    }

    // ---------------------------------------------------------------- client responses

    /**
     * Frames and writes the response a Handler returned. Returns false when nothing reached the transport,
     * whether because the request carries no correlation, the connection is gone, framing failed, or the
     * transport refused it. A Handler that answers some other way returns void and Runtime sends nothing.
     *
     * <p>Runtime owns whatever the codec framed from the moment it returns until the transport accepts it,
     * and releases it on every path that does not reach acceptance.
     */
    private boolean send(ClientHandlerContext context, Object result, int errorCode, boolean failOnEncodingError) {
        if (errorCode == 0 && result == null) return false; // A null result answers nothing; it is not a failure.
        if (context.requestId() == 0) {
            report(RUNTIME_EXECUTION_ERROR, context, new IllegalStateException("Client request carries no correlation to answer"));
            return false;
        }
        Connection connection = context.connection();
        if (connection == null) {
            report(RUNTIME_EXECUTION_ERROR, context, new IllegalStateException("Client request has no connection to answer on"));
            return false;
        }
        Object framed;
        try {
            framed = errorCode == 0 ? clientResults.encode(context, result) : clientResults.encodeError(context, errorCode);
            if (framed == null) throw new IllegalStateException("ClientResultCodec returned null");
        } catch (Throwable cause) {
            report(RUNTIME_EXECUTION_ERROR, context, cause);
            // One attempt to tell the peer that the request failed; a failing error framing is only reported.
            return failOnEncodingError && send(context, null, RUNTIME_EXECUTION_ERROR, false);
        }
        try {
            // N-WRITE-03: a refused write never consumed the message, so Runtime still owns what the codec
            // framed and must release it. Only ACCEPTED hands that ownership share to the transport.
            if (connection.write(framed) == WriteStatus.ACCEPTED) return true;
            ReferenceCountUtil.release(framed);
            return false;
        } catch (Throwable cause) {
            // A throw is neither acceptance nor refusal, so ownership is unknown; releasing a share the
            // transport may already hold would corrupt a pooled buffer, which is worse than leaking one.
            report(RUNTIME_EXECUTION_ERROR, context, cause);
            return false;
        }
    }

    // ---------------------------------------------------------------- events and calls

    private InvocationContext inherited(Context source) {
        return source instanceof InvocationContext i ? i : new DefaultInvocationContext(0, 0, 0, 0, Metadata.empty());
    }

    private void publish(Event event) {
        Objects.requireNonNull(event);
        validate(event.routeDomain(), event.routeKey());
        EventSubscribers subscribers = events.get(event.getClass());
        if (subscribers == null) return; // nothing would run: no Route task, no allocation
        // Listeners run on the event's Route. One that declared another Domain owns state this Route does
        // not, so publishing here would hand it that state outside its own serialization.
        if (subscribers.requiredDomain() != 0 && subscribers.requiredDomain() != event.routeDomain())
            throw failure(ROUTE_DOMAIN_MISMATCH);
        InvocationContext parent = inherited(RuntimeContexts.ownedBy(this) ? Contexts.current() : null);
        var context = new DefaultEventContext(event, parent.businessIdType(), parent.businessId(), parent.metadata());
        int error = submit(context, () -> {
            for (var handler : subscribers.listeners()) {
                try { handler.invoke(event); }
                catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
            }
        }, false);
        if (error != 0) throw failure(error);
    }

    public <T> void call(int domain, long key, Supplier<T> action, RouteCallback<T> callback) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(callback);
        if (!RuntimeContexts.ownedBy(this)) throw new IllegalStateException("call requires a current context owned by this runtime");
        Context source = Contexts.current();
        InvocationContext inherited = inherited(source);
        var target = new DefaultRouteCallContext(domain, key, inherited.businessIdType(), inherited.businessId(), inherited.metadata());
        int error = submit(target, () -> {
            T result;
            try { result = action.get(); }
            catch (Throwable e) {
                report(ROUTE_CALL_EXECUTION_ERROR, target, e);
                complete(source, callback, null, ROUTE_CALL_EXECUTION_ERROR, e);
                return;
            }
            complete(source, callback, result, 0, null);
        }, false);
        if (error != 0) complete(source, callback, null, error, null);
    }

    private <T> void complete(Context source, RouteCallback<T> callback, T result, int error, Throwable cause) {
        int rejected = submit(source, () -> {
            try {
                if (error == 0) callback.onSuccess(result);
                else callback.onFail(error, cause);
            } catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, source, e); }
        }, true);
        if (rejected != 0) report(ROUTE_CALLBACK_DISPATCH_FAILED, source, failure(rejected));
    }

    public <T> CapturedRouteCallback<T> callback(RouteCallback<T> callback) {
        Objects.requireNonNull(callback);
        if (!RuntimeContexts.ownedBy(this)) throw new IllegalStateException("callback requires this Runtime's current Route");
        if (closed.get() || !admission.acquire(true)) throw failure(RUNTIME_CLOSED);
        var captured = new Captured<>(Contexts.current(), callback);
        outstanding.add(captured);
        return captured;
    }

    /** One-shot capture of a source Route; completing or cancelling releases its drain slot exactly once. */
    private final class Captured<T> extends AtomicBoolean implements CapturedRouteCallback<T> {
        private final Context source;
        private final RouteCallback<T> callback;
        /** When the reservation was taken, so an abandoned one can be seen aging in diagnostics. */
        final long reservedAt = System.nanoTime();

        Captured(Context source, RouteCallback<T> callback) {
            this.source = source;
            this.callback = callback;
        }

        private void finish(T value, int error, Throwable cause) {
            if (!compareAndSet(false, true)) return;
            outstanding.remove(this);
            try { complete(source, callback, value, error, cause); }
            finally { admission.release(); }
        }

        public void onSuccess(T value) { finish(value, 0, null); }

        public void onFail(int error) { onFail(error, null); }

        @Override public void onFail(int error, Throwable cause) {
            if (error <= 0) throw new IllegalArgumentException("Failure code must be positive");
            finish(null, error, cause);
        }

        @Override public boolean cancel() {
            if (!compareAndSet(false, true)) return false;
            outstanding.remove(this);
            admission.release();
            return true;
        }
    }

    // ---------------------------------------------------------------- errors, stats, shutdown

    private void report(int code, Context context, Throwable cause) {
        errorCount.increment();
        try { errors.onError(new RuntimeError(code, context, context.routeDomain(), context.routeKey(), cause)); }
        catch (Throwable e) { LOG.log(System.Logger.Level.ERROR, "Runtime error handler failed", e); }
    }

    public void shutdown() {
        admission.stop();
        crons.close();
        timers.close();
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if (admission.accepting()) throw new IllegalStateException("Call shutdown before awaiting termination");
        if (RuntimeContexts.ownedBy(this)) throw new IllegalStateException("Cannot await termination on a Runtime Route");
        return admission.await(timeout);
    }

    public RuntimeStats stats() {
        return new RuntimeStats(admission.accepting() && !closed.get(), admission.count(), queued.sum(), running.sum(),
                completed.sum(), rejected.sum(), errorCount.sum(), executionNanos.sum(),
                outstanding.size(), oldestReservation(), domainStats());
    }

    /** One entry per registered Domain, in ascending Domain order so successive readings line up. */
    private List<DomainStats> domainStats() {
        var byDomain = new ArrayList<DomainStats>(perDomain.size());
        for (var entry : new TreeMap<>(perDomain).entrySet()) {
            DomainCounters counters = entry.getValue();
            byDomain.add(new DomainStats(entry.getKey(), counters.queued().sum(), counters.running().sum(),
                    counters.completed().sum(), counters.rejected().sum(), executorStats(entry.getKey())));
        }
        return List.copyOf(byDomain);
    }

    /** A custom executor must not break a metrics read, so a failing stats() reports nothing instead. */
    private RouteExecutorStats executorStats(int domain) {
        try {
            RouteExecutorStats reported = routes.get(domain).stats();
            return reported == null ? RouteExecutorStats.unreported() : reported;
        } catch (Throwable e) {
            return RouteExecutorStats.unreported();
        }
    }

    /** Scans the outstanding reservations, which are few unless something is leaking them. */
    private long oldestReservation() {
        long now = System.nanoTime(), oldest = 0;
        for (Captured<?> captured : outstanding) {
            long age = now - captured.reservedAt;
            if (age > oldest) oldest = age;
        }
        return oldest;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        shutdown();
        Set<RouteExecutor> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(routes.values());
        for (RouteExecutor executor : unique) {
            try { executor.close(); }
            catch (Throwable e) { LOG.log(System.Logger.Level.ERROR, "Route executor close failed", e); }
        }
    }
}
