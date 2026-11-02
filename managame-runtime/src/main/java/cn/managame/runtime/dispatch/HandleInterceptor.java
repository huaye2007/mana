package cn.managame.runtime.dispatch;

import static cn.managame.FrameworkErrorCodes.SUCCESS;

import java.lang.reflect.Method;

/**
 * Runs before and after every {@link cn.managame.runtime.annotation.HandleMethod} of a Dispatcher, inside the
 * handler's task, on its route. Every interceptor sees every handler and decides itself what to do, for example by
 * the handler's annotations:
 *
 * <pre>{@code
 * class LoginCheck implements HandleInterceptor {
 *     public int before(HandleContext context, Method handler, Object message) {
 *         return handler.isAnnotationPresent(Anonymous.class) || context.businessId() != 0 ? SUCCESS : NOT_LOGGED_IN;
 *     }
 * }
 * Dispatcher.builder(runtime).intercept(List.of(new LoginCheck(), new Metrics())).register(handlers).build();
 * }</pre>
 *
 * Interceptors run {@code before} in the order they were added, then the handler, then {@code after} in reverse
 * order. The handler answers through its context, so an answer given while it runs is sent before {@code after}.
 * An exception from either fails the request with RUNTIME_EXECUTION_ERROR, as one from the handler does, unless it
 * was answered already.
 */
public interface HandleInterceptor {
    /**
     * Before the handler. SUCCESS (0) lets it run; any other code fails the request with that code, or drops a
     * message that expects no answer, and neither the remaining interceptors nor the handler run, nor any after.
     */
    default int before(HandleContext context, Method handler, Object message) throws Exception { return SUCCESS; }

    /**
     * After the handler ran, whether it returned or threw. {@code failure} is what it threw, or null. Every after runs
     * even when another throws.
     */
    default void after(HandleContext context, Method handler, Object message, Throwable failure) throws Exception {}
}
