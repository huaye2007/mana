package cn.managame.runtime;

/**
 * Runs before and after every task of a GameRuntime, whichever entry gave it: a Dispatcher's handler, an event, an HTTP
 * endpoint, a timer, or work continued on a route with {@link GameRuntime#execute}, which is a task of its own. It runs
 * on the task's route and thread, with {@link Context#current()} the task's context. Every interceptor sees every task
 * and decides itself what to do, for example by its domain or the kind of its context:
 *
 * <pre>{@code
 * class LoginCheck implements TaskInterceptor {
 *     public void before(Context context) {
 *         if (context instanceof HandleContext request && context.domain() != LOGIN && context.businessId() == 0) {
 *             request.fail(NOT_LOGGED_IN);
 *             throw new IllegalStateException("Not logged in");   // keeps the task from running
 *         }
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
 * interceptor whose before returned, so what a before sets up an after always takes down. An exception from either is
 * handled as one from the task: a request fails with RUNTIME_EXECUTION_ERROR, unless it was answered already, and the
 * exception goes to the error handler. A before that throws keeps the task and the later befores from running, so one
 * refuses a request by failing it and throwing. Every after runs even when another throws.
 */
public interface TaskInterceptor {
    /** Before the task. */
    default void before(Context context) throws Exception {}

    /**
     * After the task returned or threw, or a later interceptor's before threw. {@code failure} is what was thrown, or
     * null. An answer the task gave is already on its way.
     */
    default void after(Context context, Throwable failure) throws Exception {}
}
