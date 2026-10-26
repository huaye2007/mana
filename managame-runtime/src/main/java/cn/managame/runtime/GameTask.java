package cn.managame.runtime;

/** Business logic run by {@link GameRuntime#execute}. */
@FunctionalInterface
public interface GameTask<C extends Context> {
    /** Runs on the context's route; an exception goes to the runtime's error handler. */
    void run(C context) throws Exception;
}
