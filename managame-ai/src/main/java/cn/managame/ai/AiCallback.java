package cn.managame.ai;

/**
 * Receives the outcome of a unary {@link AiClient#call}. Never invoked on the calling thread; in an application on
 * {@code managame-runtime}, continue game logic via {@code runtime.continuation().execute(...)}.
 */
@FunctionalInterface
public interface AiCallback<T> {
    void onResponse(AiResult<T> result);
}
