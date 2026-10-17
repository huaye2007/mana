package cn.managame.runtime.route;

/** Result of a Route call or captured asynchronous completion, delivered on the source Route. */
public interface RouteCallback<T> {
    void onSuccess(T result);

    void onFail(int frameworkErrorCode);

    /**
     * Failure with the exception that caused it, when the Runtime has one (for example the target action of
     * {@code call} threw). The default ignores the cause and calls {@link #onFail(int)}.
     */
    default void onFail(int frameworkErrorCode, Throwable cause) { onFail(frameworkErrorCode); }
}
