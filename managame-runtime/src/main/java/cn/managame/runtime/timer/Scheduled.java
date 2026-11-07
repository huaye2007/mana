package cn.managame.runtime.timer;

/** A timer that may still fire. */
public interface Scheduled {
    /**
     * Stops it, a firing handed to the runtime included unless its task has begun, so a timer cancelled on its own route
     * does not run afterwards; from another thread, one may be beginning as this returns. False when it had already
     * stopped, or a one-shot timer's task has begun.
     */
    boolean cancel();

    /** Whether it will fire no more: cancelled, or a one-shot timer whose task has begun or was refused. */
    boolean isDone();
}
