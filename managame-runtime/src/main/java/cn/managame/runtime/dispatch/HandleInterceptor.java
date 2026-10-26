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
 * Dispatcher.builder(runtime).intercept(new LoginCheck(), new Metrics()).register(...).build();
 * }</pre>
 *
 * Interceptors run {@code before} in the order they were added, then the handler, then {@code after} in reverse
 * order, then the handler's result is sent. An exception from either fails the request with
 * RUNTIME_EXECUTION_ERROR, as one from the handler does.
 */
public interface HandleInterceptor {
    /**
     * Before the handler. SUCCESS (0) lets it run; any other code fails the request with that code, or drops a
     * message that expects no answer, and neither the remaining interceptors nor the handler run, nor any after.
     */
    default int before(HandleContext context, Method handler, Object message) throws Exception { return SUCCESS; }

    /**
     * After the handler ran, whether it returned or threw, before its result is sent. {@code result} is what it
     * returned: null for void, or when it answers later through the context. {@code failure} is what it threw, or
     * null. Every after runs even when another throws.
     */
    default void after(HandleContext context, Method handler, Object message, Object result, Throwable failure) throws Exception {}
}
