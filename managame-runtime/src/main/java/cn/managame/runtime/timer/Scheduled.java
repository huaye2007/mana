package cn.managame.runtime.timer;

/** A timer that may still fire. */
public interface Scheduled {
    /**
     * Stops future firings; false when it had already stopped. A firing already handed to the runtime still
     * runs.
     */
    boolean cancel();

    /** Whether it will fire no more: cancelled, or a one-shot timer that fired. */
    boolean isDone();
}
