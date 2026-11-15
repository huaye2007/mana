package cn.managame.runtime.dispatch;

import cn.managame.runtime.annotation.HandleMethod;
import java.lang.reflect.Method;

/**
 * Runs before and after every {@link HandleMethod} of a Dispatcher, with the handler's method and the message: what is
 * about a request, such as a login check, a limit by kind of message, or a feature switched off while it is being
 * fixed.
 *
 * <pre>{@code
 * class FeatureSwitch implements HandleInterceptor {
 *     private final Set<Class<?>> closed = ConcurrentHashMap.newKeySet();   // opened and closed while the server runs
 *
 *     public boolean before(HandleContext context, Method handler, Object message) {
 *         if (!closed.contains(handler.getDeclaringClass())) return true;
 *         context.fail(FEATURE_CLOSED);
 *         return false;
 *     }
 * }
 * Dispatcher.builder(runtime).intercept(List.of(new FeatureSwitch())).register(handlers).build();
 * }</pre>
 *
 * It runs once for each message, right around the handler, on the handler's route and thread, so it may read what the
 * route owns, such as the player's level; and it serves every entry that dispatches to the Dispatcher, a client
 * gateway and an entry for other servers alike. It does not run for what a
 * {@link cn.managame.runtime.TaskInterceptor} also sees: a task that continues the request later, an event, a timer.
 * Nor for a message that never reaches a handler: one with no handler, one that cannot be routed, one the runtime
 * refuses.
 *
 * <p>The befores run in the order the interceptors were added, inside the runtime's TaskInterceptors, then the handler,
 * then in reverse order the after of each interceptor whose before returned true. A before that returns false keeps
 * the handler and the later befores from running, and no error handler hears of it, since a refusal is no error; the
 * interceptor answers the request it refuses, or else it fails with RUNTIME_EXECUTION_ERROR, since a request is always
 * answered.
 *
 * <p>An exception from either is handled as one from the handler: the request fails with RUNTIME_EXECUTION_ERROR,
 * unless it was answered already, and the exception goes to the runtime's error handler. A before that throws also
 * keeps the handler and the later befores from running. Every after runs even when another throws.
 */
public interface HandleInterceptor {
    /**
     * Before the handler; true lets it run, false refuses the message. {@code handler} is the handler's method, for its
     * class, its name and the annotations the application put on either; {@code message} is what it will be given.
     */
    default boolean before(HandleContext context, Method handler, Object message) throws Exception { return true; }

    /**
     * After the handler returned or threw, or a later interceptor's before refused the message or threw. {@code failure}
     * is what was thrown, or null, as after a refusal. An answer the handler returned is already on its way; a void
     * handler may answer later still.
     */
    default void after(HandleContext context, Method handler, Object message, Throwable failure) throws Exception {}
}
