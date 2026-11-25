package cn.managame.ai;

/**
 * Receives the outcome of a unary {@link AiClient#call}. Dispatched through the client's configured
 * {@link cn.managame.ai.spi.AiDispatcher} (for example back onto the caller's {@code GameRuntime} route) — never
 * invoked synchronously on the calling thread or on I/O selector / timer threads.
 */
@FunctionalInterface
public interface AiCallback<T> {
    void onResponse(AiResult<T> result);
}
