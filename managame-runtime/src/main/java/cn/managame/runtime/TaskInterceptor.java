package cn.managame.runtime;

/**
 * Runs before and after every task of a GameRuntime, whichever entry gave it: a Dispatcher's handler, an event, an HTTP
 * endpoint, a timer, or work continued on a route with {@link GameRuntime#execute}, which is a task of its own. It runs
 * on the task's route and thread, with {@link Context#current()} the task's context. Every interceptor sees every task
 * and decides itself what to do, for example by its domain or the kind of its context:
 *
 * <pre>{@code
 * class LoginCheck implements TaskInterceptor {
 *     public boolean before(Context context) {
 *         if (context instanceof HandleContext request && context.domain() != LOGIN && context.businessId() == 0) {
 *             request.fail(NOT_LOGGED_IN);
 *             return false;
 *         }
 *         return true;
 *     }
 * }
 * class SavePlayer implements TaskInterceptor {
 *     public void after(Context context, Throwable failure) {
 *         if (context.domain() == PLAYER && failure == null) players.get(context.routeKey()).saveIfDirty();
 *     }
 * }
 * GameRuntime.builder().domain(PLAYER, RouteExecutors.virtualThreads()).intercept(List.of(new LoginCheck(), new SavePlayer()));
 * }</pre>
 *
 * The befores run in the order the interceptors were added, then the task, then in reverse order the after of each
 * interceptor whose before returned true, so what a before sets up an after always takes down. A before that returns
 * false refuses the task: it and the later befores do not run, and no error handler hears of it, since a refusal is no
 * error. The interceptor answers a request it refuses, or else it fails with RUNTIME_EXECUTION_ERROR, since a request
 * is always answered.
 *
 * <p>An exception from either is handled as one from the task: a request fails with RUNTIME_EXECUTION_ERROR, unless it
 * was answered already, and the exception goes to the error handler. A before that throws also keeps the task and the
 * later befores from running. Every after runs even when another throws.
 */
public interface TaskInterceptor {
    /** Before the task; true lets it run, false refuses it. */
    default boolean before(Context context) throws Exception { return true; }

    /**
     * After the task returned or threw, or a later interceptor's before refused it or threw. {@code failure} is what
     * was thrown, or null, as after a refusal. An answer the task gave is already on its way.
     */
    default void after(Context context, Throwable failure) throws Exception {}
}
