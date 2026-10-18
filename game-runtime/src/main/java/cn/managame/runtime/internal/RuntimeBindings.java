package cn.managame.runtime.internal;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.handler.ClientResultCodec;
import cn.managame.runtime.handler.HandlerArgumentBinding;
import cn.managame.runtime.handler.HandlerContextFactory;
import cn.managame.runtime.http.HttpContextFactory;
import cn.managame.runtime.http.HttpResultCodec;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteKeyBinding;
import cn.managame.runtime.error.RuntimeErrorHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Immutable, validated invocation tables produced by RuntimeCompiler. */
final class RuntimeBindings {
    private RuntimeBindings() {}

    /** Everything a DefaultGameRuntime needs, frozen at build. */
    record Compiled(Map<Integer, RouteExecutor> routes, ProtocolRegistry protocols,
                    Map<Class<?>, RouteKeyBinding<?>> routeKeys, Map<Class<?>, HandlerBinding> handlers,
                    HandlerContextFactory handlerContexts, ClientResultCodec clientResults,
                    Map<Class<?>, EventSubscribers> events,
                    List<CronBinding> crons, Map<HttpEndpoint, HttpBinding> http, HttpContextFactory httpContexts,
                    HttpResultCodec httpResults, RuntimeErrorHandler errors, boolean executionTiming) {}

    /**
     * @param returnsResult the method returns the request's registered response, which Runtime frames and
     *                      writes; a void method answers nothing, or answers through the connection itself
     */
    record HandlerBinding(int domain, Class<?> contextType, List<HandlerArgumentBinding<?>> arguments,
                          boolean returnsResult, MethodHandle handle) {
        private static final Object[] NO_ARGUMENTS = new Object[0];

        Object[] resolve(HandlerContext context) {
            if (arguments.isEmpty()) return NO_ARGUMENTS;
            Object[] values = new Object[arguments.size()];
            for (int i = 0; i < values.length; i++) values[i] = arguments.get(i).resolve(context);
            return values;
        }

        Object invoke(Object context, Object message, Object[] arguments) throws Throwable {
            return handle.invokeExact(context, message, arguments);
        }
    }

    record HttpEndpoint(String method, String path) {}

    record HttpBinding(int domain, Class<?> contextType, boolean returnsResult, MethodHandle handle, HttpRouteKey routeKey,
                       Function<FullHttpRequest, Object> decoder) {
        Object invoke(Object context, Object request) throws Throwable { return handle.invokeExact(context, request); }
    }

    /**
     * One event type's listeners, in ascending order.
     *
     * @param requiredDomain the Domain its listeners declared, or 0 when none did; an event published to
     *                       another Domain would run them on a Route that does not own their state
     */
    record EventSubscribers(int requiredDomain, List<EventBinding> listeners) {}

    record EventBinding(int order, MethodHandle handle) {
        void invoke(Object event) throws Throwable { handle.invokeExact(event); }
    }

    record CronKey(Class<?> type, String methodName) {}

    record CronBinding(CronKey id, int domain, long key, CronSchedule schedule, MethodHandle handle) {
        void invoke() throws Throwable { handle.invokeExact(); }
    }
}
