package cn.managame.ai;

/**
 * Receives incremental SSE events and the terminal outcome of {@link AiClient#stream}. {@link #onEvent} is called
 * sequentially as events arrive and never after {@link #onComplete} or {@link AiCall#cancel()}. {@link #onComplete}
 * is called exactly once, off the calling thread, with {@link AiResult#isSuccess()} on normal end-of-stream or a
 * failure code otherwise.
 */
public interface AiStreamListener {
    void onEvent(AiStreamEvent event);

    void onComplete(AiResult<Void> result);
}
