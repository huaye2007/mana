package cn.managame.runtime.diagnostic;

/**
 * The Route task that has been running longest, so a reading can name what is stuck instead of only
 * counting what is busy. A Handler blocked on a lock or a socket otherwise shows up as a running count that
 * never falls and a queue that climbs to refusal, with nothing to say which Route it is.
 *
 * <p>The age is measured from the moment the task entered its Route, not from when the message arrived, so
 * it is execution time and does not include queueing. It is a lower bound in one respect only: the
 * publication slots are finite, so under sustained saturation a task may run untracked. What is reported
 * was always really running, and its age is never overstated.
 *
 * @param routeDomain the Domain of the Route running it, 0 when nothing is reported
 * @param routeKey the Key of that Route, 0 when nothing is reported; 0 is never a valid Key
 * @param runningNanos how long it has been running, 0 when nothing is reported
 */
public record RunningTask(int routeDomain, long routeKey, long runningNanos) {
    private static final RunningTask NONE = new RunningTask(0, 0, 0);

    /** Nothing tracked is running. Every reading is 0, which no running task can report. */
    public static RunningTask none() { return NONE; }
}
