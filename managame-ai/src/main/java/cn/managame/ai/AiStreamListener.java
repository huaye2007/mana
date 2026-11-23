package cn.managame.ai;

/**
 * Receives incremental events from a streaming {@link AiClient#stream} call. {@link #onChunk} runs zero or more times
 * in order, followed by either {@link #onComplete()} or {@link #onError(int, String)} exactly once, and never on the
 * calling thread.
 */
public interface AiStreamListener {
    void onChunk(AiChunk chunk);

    default void onComplete() {}

    void onError(int errorCode, String message);
}
