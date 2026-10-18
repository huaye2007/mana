package cn.managame.runtime.handler;

import cn.managame.runtime.context.HandlerContext;

/**
 * Watches Handler methods execute, without being able to change what they do. It is the place for
 * per-Handler latency and counts, which nothing else in Runtime can supply: {@code RuntimeStats} is
 * aggregate, and {@code RuntimeErrorHandler} sees only failures.
 *
 * <p>Exactly one of {@link #onFinish} and {@link #onError} follows each {@link #onStart}, all three on the
 * request's Route, inside its Route task, with the same context the Handler received. Observing cannot
 * intervene: it cannot skip the method, change its result, or turn its own failure into the Handler's —
 * a callback that throws is reported to the error handler and otherwise ignored.
 *
 * <p>It runs where the business runs, so it must not block, must not touch another Route's state, and
 * should do no more than record. Nested same-Route dispatch nests observations, so those durations
 * overlap. A dispatch rejected before admission is never observed, because its method never ran.
 */
public interface HandlerObserver {
    /** Before the method, after arguments are resolved and the context is bound. */
    default void onStart(HandlerContext context) {}

    /** After the method returned, with the monotonic duration of the method alone. */
    void onFinish(HandlerContext context, long elapsedNanos);

    /** After the method threw. The error handler receives the same failure separately. */
    void onError(HandlerContext context, long elapsedNanos, Throwable cause);
}
