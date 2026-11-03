package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.HANDLER_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.INVALID_ROUTE_KEY;
import static cn.managame.FrameworkErrorCodes.RUNTIME_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.SUCCESS;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.Route;
import cn.managame.runtime.annotation.AnnotatedMethod;
import cn.managame.runtime.annotation.HandleMethod;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs messages on their {@link HandleMethod} handlers, whatever protocol carried them, so one Dispatcher can serve
 * every entry of an application. Handlers are registered once, at startup, by passing the objects that declare
 * them:
 *
 * <pre>{@code
 * Dispatcher dispatcher = Dispatcher.builder(runtime).register(List.of(shop, guildSystem, login)).build();
 *
 * // In the application's own ConnectionHandler, which knows its packet format:
 * dispatcher.dispatch(session(connection), packet.body(),
 *         result -> connection.write(new Packet(packet.seq(), result)),
 *         (errorCode, args) -> connection.write(Packet.error(packet.seq(), errorCode, args)));
 * }</pre>
 *
 * A message runs in its handler's domain, on the route key its handler's routeKey member holds, or else the domain's
 * resolver finds for it and the source, * acting for the source's business identity, and its handler answers through its {@link HandleContext}, while it
 * runs or later from any thread. Messages are matched by exact class. One with no handler fails with
 * HANDLER_NOT_FOUND, one that cannot be routed with INVALID_ROUTE_KEY, one the runtime refuses with the runtime's
 * code, and a handler that throws with RUNTIME_EXECUTION_ERROR. {@link HandleInterceptor}s run before and after
 * every handler.
 */
public final class Dispatcher {
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime.dispatch");
    private final GameRuntime runtime;
    private final Map<Class<?>, AnnotatedMethod> handlers;
    private final HandleInterceptor[] interceptors;

    private Dispatcher(Builder b) {
        runtime = b.runtime;
        handlers = Map.copyOf(b.handlers);
        interceptors = b.interceptors.toArray(HandleInterceptor[]::new);
        LOG.log(Level.INFO, () -> "Dispatcher with " + handlers.size() + " handlers and " + interceptors.length + " interceptors");
        handlers.forEach((type, handler) -> LOG.log(Level.DEBUG, () -> type.getName() + " handled by " + handler + " in domain " + handler.domain()));
    }

    public static Builder builder(GameRuntime runtime) { return new Builder(runtime); }

    /** The runtime the handlers run on. */
    public GameRuntime runtime() { return runtime; }

    /** Dispatches a message that expects no answer. */
    public void dispatch(Context source, Object message) { dispatch(source, message, null, null); }

    /**
     * Dispatches {@code message} from {@code source}; {@code onReply} and {@code onFail} deliver its answer in
     * the application's format, at most one of them once, from whichever thread answers. Both null for a message
     * that expects no answer.
     */
    public void dispatch(Context source, Object message, Consumer<Object> onReply, FailCallback onFail) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        if ((onReply == null) != (onFail == null)) throw new IllegalArgumentException("Give both onReply and onFail, or neither");
        AnnotatedMethod handler = handlers.get(message.getClass());
        if (handler == null) {
            reject(message, onFail, HANDLER_NOT_FOUND, "no handler");
            return;
        }
        Route route;
        try {
            route = handler.route(runtime, source, message);
        } catch (RuntimeException e) {
            reject(message, onFail, INVALID_ROUTE_KEY, String.valueOf(e));
            return;
        }
        HandleContext context = new HandleContext(route, source, onReply, onFail);
        try {
            runtime.execute(context, task -> run(handler, task, message));
        } catch (GameRuntimeException refused) {
            // execute answered the request with the refusal's code
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Cannot run " + handler, e);
            context.fail(RUNTIME_EXECUTION_ERROR);
        }
    }

    /** The handler's task: interceptors before, the handler, then interceptors after. */
    private void run(AnnotatedMethod handler, HandleContext context, Object message) throws Exception {
        Method method = handler.method();
        for (HandleInterceptor interceptor : interceptors) {
            int errorCode = interceptor.before(context, method, message);
            if (errorCode != SUCCESS) {
                context.fail(errorCode);
                return;
            }
        }
        Throwable failure = null;
        try {
            handler.invoke(context, message);
        } catch (Exception | Error e) {
            failure = e;
        }
        Throwable thrown = failure;
        for (int i = interceptors.length - 1; i >= 0; i--) {
            try {
                interceptors[i].after(context, method, message, failure);
            } catch (Exception | Error e) {
                if (thrown == null) thrown = e;
                else thrown.addSuppressed(e);
            }
        }
        switch (thrown) {
            case null -> { }
            case Exception e -> throw e;
            case Error e -> throw e;
            default -> throw new UndeclaredThrowableException(failure);
        }
    }

    private static void reject(Object message, FailCallback onFail, int errorCode, String reason) {
        if (LOG.isLoggable(Level.DEBUG)) LOG.log(Level.DEBUG, "Rejected " + message.getClass().getName() + ": " + reason);
        if (onFail != null) onFail.onFail(errorCode, HandleContext.NO_ARGS);
    }

    public static final class Builder {
        private final GameRuntime runtime;
        private final Map<Class<?>, AnnotatedMethod> handlers = new HashMap<>();
        private final List<HandleInterceptor> interceptors = new ArrayList<>();

        private Builder(GameRuntime runtime) { this.runtime = Objects.requireNonNull(runtime, "runtime"); }

        /** Adds interceptors for every handler; their before methods run in the order they were added. */
        public Builder intercept(Collection<? extends HandleInterceptor> interceptors) {
            for (HandleInterceptor interceptor : interceptors) this.interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * Registers every {@link HandleMethod} of each object. Two handlers for one message class, a handler that
         * returns a value, declares a context other than HandleContext or its supertypes, runs in an unregistered
         * domain or routes by a member its message lacks, throw IllegalArgumentException.
         */
        public Builder register(Collection<?> targets) {
            for (Object target : targets) {
                for (AnnotatedMethod handler : AnnotatedMethod.scan(target, HandleMethod.class, HandleMethod::routeKey)) {
                    if (handler.method().getReturnType() != void.class)
                        throw new IllegalArgumentException(handler + " must return void and answer through its HandleContext");
                    handler.requireContext(HandleContext.class);
                    runtime.checkDomain(handler.domain());
                    AnnotatedMethod existing = handlers.putIfAbsent(handler.messageType(), handler);
                    if (existing != null)
                        throw new IllegalArgumentException(handler.messageType().getName() + " is handled by both " + existing + " and " + handler);
                }
            }
            return this;
        }

        public Dispatcher build() { return new Dispatcher(this); }
    }
}
