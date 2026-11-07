package cn.managame.runtime;

/** Business logic run by {@link GameRuntime#execute}. */
@FunctionalInterface
public interface GameTask<C extends Context> {
    /** Runs on the context's route; an exception goes to the runtime's error handler. */
    void run(C context) throws Exception;

    /**
     * Runs instead of {@link #run} when the task will not run: on its route, after the interceptors, when one refused it
     * or threw before it; on the caller of {@link GameRuntime#execute}, before that throws, when the runtime did not
     * accept it. For what a task holds, such as a buffer, which {@code run} gives back as it ends and this gives back in
     * its place. On the route, an exception goes to the error handler.
     */
    default void refused(C context) {}
}
