package cn.managame.runtime.internal;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Context;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.DefaultEventContext;
import cn.managame.runtime.context.DefaultClientHandlerContext;
import cn.managame.runtime.context.ClientHandlerContext;
import cn.managame.runtime.context.DefaultRpcHandlerContext;
import cn.managame.runtime.diagnostic.RuntimeStats;
import cn.managame.runtime.context.DefaultInvocationContext;
import cn.managame.runtime.context.DefaultRouteCallContext;
import cn.managame.runtime.context.DefaultTimerContext;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.context.InvocationContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.error.RuntimeError;
import cn.managame.runtime.error.RuntimeErrorHandler;
import cn.managame.runtime.event.Event;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.event.Events;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.handler.HandlerContextFactory;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteKeyBinding;
import cn.managame.runtime.route.RouteKeyRegistry;
import cn.managame.runtime.timer.CronScheduler;
import cn.managame.runtime.timer.RuntimeTimer;
import cn.managame.runtime.http.HttpDispatcher;
import cn.managame.runtime.http.HttpContextFactory;
import cn.managame.runtime.http.HttpResultCodec;
import cn.managame.network.connection.Connection;

import cn.managame.core.*;
import static cn.managame.core.FrameworkErrorCodes.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.time.Duration;
import java.util.function.Supplier;
import cn.managame.runtime.internal.RuntimeCompiler.*;
final class DefaultGameRuntime implements GameRuntime {
    private final Map<Integer, RouteExecutor> routes;
    private final ProtocolRegistry protocols;
    private final Map<Class<?>, RouteKeyBinding<?>> keys;
    private final Map<Class<?>, HandlerBinding> handlers;
    private final HandlerContextFactory handlerContexts;
    private final Map<Class<?>, List<EventBinding>> events;
    private final RuntimeErrorHandler errors;
    private final RuntimeTimers timers;
    private final DefaultCronScheduler crons;
    private final HttpDispatcher http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RuntimeActivity activity = new RuntimeActivity();
    private final AtomicLong queued = new AtomicLong(), running = new AtomicLong();
    private final LongAdder completed = new LongAdder(), rejected = new LongAdder(), errorCount = new LongAdder(), executionNanos = new LongAdder();
    DefaultGameRuntime(Map<Integer, RouteExecutor> routes, ProtocolRegistry protocols,
        Map<Class<?>, RouteKeyBinding<?>> keys, Map<Class<?>, HandlerBinding> handlers,
        HandlerContextFactory handlerContexts,
        Map<Class<?>, List<EventBinding>> events, List<CronBinding> crons,
        Map<HttpEndpoint, HttpBinding> httpHandlers, HttpContextFactory httpContexts, HttpResultCodec httpResults, RuntimeErrorHandler errors) {
        this.routes=routes; this.protocols=protocols; this.keys=keys; this.handlers=handlers; this.events=events; this.errors=errors;
        this.handlerContexts = handlerContexts;
        http = new RuntimeHttp(httpHandlers, httpContexts, httpResults,
                () -> closed.get() || (!activity.accepting() && !RuntimeContexts.ownedBy(this)),
                (context, action) -> submit(context, action, false),
                (context, cause) -> report(RUNTIME_EXECUTION_ERROR, context, cause));
        timers = new RuntimeTimers(this::validate, (context, action) -> submit(context, action, false),
            error -> report(error.errorCode(), error.context(), error.cause()));
        this.crons = new DefaultCronScheduler(crons, timers,
            (binding, error) -> report(RUNTIME_EXECUTION_ERROR,
                new DefaultTimerContext(binding.domain(), binding.key()), error));
        try { this.crons.start(); }
        catch (RuntimeException | Error e) { this.crons.close(); timers.close(); throw e; }
    }
    public ProtocolRegistry protocols() { return protocols; }
    public RouteKeyRegistry routeKeys() { return message -> { var b = keys.get(Objects.requireNonNull(message).getClass()); return b == null ? 0 : extractKey(b, message); }; }
    private static <T> long extractKey(RouteKeyBinding<T> binding, Object message) {
        return binding.extractor().extract(binding.messageType().cast(message));
    }
    public EventBus eventBus() { return this::publish; }
    public RuntimeTimer timer() { return timers; }
    public CronScheduler cron() { return crons; }
    public HttpDispatcher http() { return http; }
    private void validate(int domain, long key) {
        requireAdmission();
        if (!routes.containsKey(domain)) throw failure(ROUTE_DOMAIN_MISMATCH);
        if (key == 0) throw failure(INVALID_ROUTE_KEY);
    }
    private static RuntimeDispatchException failure(int code) { return new RuntimeDispatchException(code, "Runtime dispatch rejected: " + code); }
    private void requireAdmission() {
        if (closed.get() || (!activity.accepting() && !RuntimeContexts.ownedBy(this))) {
            rejected.increment(); throw failure(RUNTIME_CLOSED);
        }
    }
    private int submit(Context context, Runnable action, boolean allowClosed) {
        if (closed.get() && !allowClosed) return RUNTIME_CLOSED;
        RouteExecutor executor = routes.get(context.routeDomain());
        if (executor == null) return ROUTE_DOMAIN_MISMATCH;
        if (context.routeKey() == 0) return INVALID_ROUTE_KEY;
        if (!activity.acquire(allowClosed || RuntimeContexts.ownedBy(this))) { rejected.increment(); return RUNTIME_CLOSED; }
        queued.incrementAndGet();
        Runnable bound = () -> {
            queued.decrementAndGet(); running.incrementAndGet(); long started = System.nanoTime();
            try { RuntimeContexts.run(this, context, action); }
            finally {
                executionNanos.add(System.nanoTime() - started); running.decrementAndGet();
                completed.increment(); activity.release();
            }
        };
        Context current = Contexts.currentOrNull();
        if (RuntimeContexts.ownedBy(this) && current.routeDomain() == context.routeDomain() && current.routeKey() == context.routeKey()) {
            bound.run(); return 0;
        }
        try {
            int result = switch (Objects.requireNonNull(executor.tryExecute(context.routeDomain(), context.routeKey(), bound))) {
                case ACCEPTED -> 0;
                case OVERLOADED -> ROUTE_EXECUTOR_OVERLOADED;
                case CLOSED -> ROUTE_EXECUTOR_CLOSED;
            };
            if (result != 0) { queued.decrementAndGet(); activity.release(); rejected.increment(); }
            return result;
        } catch (Throwable e) {
            queued.decrementAndGet(); activity.release(); rejected.increment();
            report(RUNTIME_EXECUTION_ERROR, context, e); return RUNTIME_EXECUTION_ERROR;
        }
    }
    public void dispatch(Connection connection, Object message) {
        dispatch(connection, Metadatas.empty(), message);
    }
    public void dispatch(Connection connection, Metadata metadata, Object message) {
        Objects.requireNonNull(metadata);
        if (handlerContexts == null) {
            requireAdmission(); Objects.requireNonNull(message);
            var handler = handlers.get(message.getClass());
            if (handler == null) throw failure(HANDLER_NOT_FOUND);
            var binding = keys.get(message.getClass());
            long key = binding == null ? 0 : extractKey(binding, message);
            dispatch(new DefaultClientHandlerContext(handler.domain(), key, 0, 0, metadata, message, connection));
            return;
        }
        requireAdmission();
        Objects.requireNonNull(message, "message");
        var handler = handlers.get(message.getClass());
        if (handler == null) throw failure(HANDLER_NOT_FOUND);
        ClientHandlerContext context = Objects.requireNonNull(handlerContexts.create(handler.domain(), connection, metadata, message),
                "HandlerContextFactory returned null");
        if (context.routeDomain() != handler.domain()) throw failure(ROUTE_DOMAIN_MISMATCH);
        if (context.message() != message || context.connection() != connection || context.metadata() != metadata) throw failure(HANDLER_CONTEXT_MISMATCH);
        dispatch(context);
    }
    public void dispatch(Connection connection, int businessIdType, long businessId, Object message) {
        requireAdmission();
        Objects.requireNonNull(message, "message");
        var handler = handlers.get(message.getClass());
        if (handler == null) throw failure(HANDLER_NOT_FOUND);
        var keyBinding = keys.get(message.getClass());
        long key = keyBinding == null ? 0 : extractKey(keyBinding, message);
        dispatch(new DefaultClientHandlerContext(handler.domain(), key, businessIdType, businessId, Metadatas.empty(), message, connection));
    }
    public void dispatch(Connection connection, long routeKey, Object message) {
        dispatch(connection, routeKey, 0, 0L, message);
    }
    public void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Object message) {
        dispatch(connection, routeKey, businessIdType, businessId, Metadatas.empty(), message);
    }
    public void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Metadata metadata, Object message) {
        requireAdmission();
        Objects.requireNonNull(message, "message");
        var handler = handlers.get(message.getClass());
        if (handler == null) throw failure(HANDLER_NOT_FOUND);
        dispatch(new DefaultClientHandlerContext(handler.domain(), routeKey, businessIdType, businessId, metadata, message, connection));
    }
    public void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey,
                            int businessIdType, long businessId, Metadata metadata, Object message) {
        requireAdmission(); Objects.requireNonNull(message);
        var handler = handlers.get(message.getClass());
        if (handler == null) throw failure(HANDLER_NOT_FOUND);
        dispatch(new DefaultRpcHandlerContext(handler.domain(), routeKey, businessIdType, businessId, metadata,
                message, sourceNodeId, sourceSlotId, command, requestId));
    }
    public void dispatch(HandlerContext context) {
        Objects.requireNonNull(context); validate(context.routeDomain(), context.routeKey());
        Object message = Objects.requireNonNull(context.message());
        var handler = handlers.get(message.getClass());
        if (handler == null) throw failure(HANDLER_NOT_FOUND);
        if (handler.domain() != context.routeDomain()) throw failure(ROUTE_DOMAIN_MISMATCH);
        if (handler.contextType() != null && !handler.contextType().isInstance(context)) throw failure(HANDLER_CONTEXT_MISMATCH);
        Object[] arguments = handler.resolve(context);
        int error = submit(context, () -> {
            try { handler.invoke(context, message, arguments); } catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
        }, false);
        if (error != 0) throw failure(error);
    }
    private InvocationContext inherited(Context source) {
        return source instanceof InvocationContext i ? i : new DefaultInvocationContext(0, 0, 0, 0, Metadatas.empty());
    }
    private void publish(Event event) {
        Objects.requireNonNull(event); validate(event.routeDomain(), event.routeKey());
        InvocationContext parent = inherited(RuntimeContexts.ownedBy(this) ? Contexts.current() : null);
        var context = new DefaultEventContext(event, parent.businessIdType(), parent.businessId(), parent.metadata());
        int error = submit(context, () -> {
            for (var handler : events.getOrDefault(event.getClass(), List.of())) {
                try { handler.invoke(event); } catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, context, e); }
            }
        }, false);
        if (error != 0) throw failure(error);
    }
    public <T> void call(int domain, long key, Supplier<T> action, RouteCallback<T> callback) {
        Objects.requireNonNull(action); Objects.requireNonNull(callback);
        if (!RuntimeContexts.ownedBy(this)) throw new IllegalStateException("call requires a current context owned by this runtime");
        Context source = Contexts.current();
        InvocationContext inherited = inherited(source);
        var target = new DefaultRouteCallContext(domain, key, inherited.businessIdType(), inherited.businessId(), inherited.metadata());
        int error = submit(target, () -> {
            T result;
            try { result = action.get(); }
            catch (Throwable e) { report(ROUTE_CALL_EXECUTION_ERROR, target, e); complete(source, callback, null, ROUTE_CALL_EXECUTION_ERROR); return; }
            complete(source, callback, result, 0);
        }, false);
        if (error != 0) complete(source, callback, null, error);
    }
    private <T> void complete(Context source, RouteCallback<T> callback, T result, int error) {
        int rejected = submit(source, () -> {
            try { if (error == 0) callback.onSuccess(result); else callback.onFail(error); }
            catch (Throwable e) { report(RUNTIME_EXECUTION_ERROR, source, e); }
        }, true);
        if (rejected != 0) report(ROUTE_CALLBACK_DISPATCH_FAILED, source, failure(rejected));
    }
    public <T> RouteCallback<T> callback(RouteCallback<T> callback) {
        Objects.requireNonNull(callback);
        if (!RuntimeContexts.ownedBy(this)) throw new IllegalStateException("callback requires this Runtime's current Route");
        if (closed.get() || !activity.acquire(true)) throw failure(RUNTIME_CLOSED);
        Context source = Contexts.current();
        AtomicBoolean done = new AtomicBoolean();
        return new RouteCallback<>() {
            private void finish(T value, int error) {
                if (!done.compareAndSet(false, true)) return;
                try { complete(source, callback, value, error); } finally { activity.release(); }
            }
            public void onSuccess(T value) { finish(value, 0); }
            public void onFail(int error) {
                if (error <= 0) throw new IllegalArgumentException("Failure code must be positive");
                finish(null, error);
            }
        };
    }
    private void report(int code, Context context, Throwable cause) {
        errorCount.increment();
        try { errors.onError(new RuntimeError(code, context, context.routeDomain(), context.routeKey(), cause)); }
        catch (Throwable e) { System.getLogger("cn.managame.runtime").log(System.Logger.Level.ERROR, "Runtime error handler or close failed", e); }
    }
    public void shutdown() {
        activity.stop();
        crons.close(); timers.close();
    }
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if (activity.accepting()) throw new IllegalStateException("Call shutdown before awaiting termination");
        if (RuntimeContexts.ownedBy(this)) throw new IllegalStateException("Cannot await termination on a Runtime Route");
        return activity.await(timeout);
    }
    public RuntimeStats stats() {
        return new RuntimeStats(activity.accepting() && !closed.get(), activity.count(), queued.get(), running.get(),
                completed.sum(), rejected.sum(), errorCount.sum(), executionNanos.sum());
    }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        shutdown();
        Events.unbind(this);
        crons.close();
        timers.close();
        Set<RouteExecutor> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(routes.values());
        for (RouteExecutor executor : unique) try { executor.close(); } catch (Throwable e) { System.getLogger("cn.managame.runtime").log(System.Logger.Level.ERROR, "Runtime error handler or close failed", e); }
    }
}
