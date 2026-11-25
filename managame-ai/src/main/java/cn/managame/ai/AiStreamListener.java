package cn.managame.ai;

/**
 * Receives incremental SSE events and the terminal outcome of {@link AiClient#stream}. Both {@link #onEvent} and
 * {@link #onComplete} are dispatched sequentially in stream order through the client's configured
 * {@link cn.managame.ai.spi.AiDispatcher} — never on the calling thread or on I/O selector / timer threads.
 * {@link #onEvent} is never called after {@link #onComplete} or {@link AiCall#cancel()}; an SSE {@code event: error}
 * frame aborts the stream and delivers a failed {@link AiResult} to {@link #onComplete}.
 */
public interface AiStreamListener {
    void onEvent(AiStreamEvent event);

    void onComplete(AiResult<Void> result);
}
