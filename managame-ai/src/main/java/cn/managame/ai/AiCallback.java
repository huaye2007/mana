package cn.managame.ai;

/** Receives the outcome of an asynchronous {@link AiClient} call, exactly once and never on the calling thread. */
@FunctionalInterface
public interface AiCallback<T> {
    void onResult(AiResult<T> result);
}
