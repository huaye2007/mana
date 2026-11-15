package cn.managame.runtime;

/**
 * The way back to a task's route for what the task began outside the runtime, whose result comes on a thread of its
 * own, such as a call to another server or an asynchronous client. The task takes it with
 * {@link GameRuntime#continuation()} and whatever brings the result hands the rest of the work to it:
 *
 * <pre>{@code
 * Continuation back = runtime.continuation();              // in the task
 * peer.call(request, (sent, response) -> {
 *     Rank rank = decode(response);                         // on the EventLoop, before the response is released
 *     back.execute(context -> show(rank));                  // on the task's route again
 * });
 * }</pre>
 *
 * What it executes is the task's own work going on, not an entry, so a full route does not refuse it and what was
 * accepted finishes what it began. Only a runtime shut down refuses it, when no executor may be left to run it; closing
 * what brings results first, such as the RPC node, whose close fails the calls still waiting, lets every one of them in
 * before that. A route goes past its limit by no more than the results on their way to it, which their source bounds,
 * as rpc does the calls waiting for one peer.
 *
 * <p>It holds the runtime and the task's context and nothing else, so it may be used from any thread, any number of
 * times: once for each result of a task that asked several servers, or for a failure in place of a result.
 */
public final class Continuation {
    private final GameRuntime runtime;
    private final Context context;

    Continuation(GameRuntime runtime, Context context) {
        this.runtime = runtime;
        this.context = context;
    }

    /**
     * Queues {@code task} on the route of the task this was taken in and returns; it later runs with that task's
     * context as {@link Context#current()}, a request's still able to answer, and counts as that request's handler.
     * Other tasks of the route may run before it, so it goes by the state as it is by then. Throws GameRuntimeException
     * with RUNTIME_CLOSED once the runtime is shut down; the task then never runs, its {@link GameTask#refused} does,
     * and a request is first failed with that code, as by {@link GameRuntime#execute}.
     */
    public void execute(GameTask<? super Context> task) { runtime.handOn(null, context, task); }
}
