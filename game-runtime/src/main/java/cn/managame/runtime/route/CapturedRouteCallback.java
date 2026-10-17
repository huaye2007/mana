package cn.managame.runtime.route;

/**
 * A callback captured on its source Route by {@code GameRuntime.callback}. Completing it once (success or
 * failure) returns to the source Route; until then it holds the Runtime's drain open.
 */
public interface CapturedRouteCallback<T> extends RouteCallback<T> {
    /**
     * Releases the capture without running the business callback, for an integration that learned
     * synchronously that the asynchronous operation never started and already reported that to its caller.
     * Returns false when the callback was already completed or cancelled.
     */
    boolean cancel();
}
