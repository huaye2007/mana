package cn.managame.ai;

/**
 * Handle to an in-flight {@link AiClient} call or stream. Safe to call from any thread.
 */
public interface AiCall {
    /**
     * Cancels the call locally and aborts the underlying HTTP request or stream. Returns {@code true} if this call
     * transitioned it to cancelled (in which case its callback receives {@link cn.managame.FrameworkErrorCodes#AI_CANCELLED}),
     * or {@code false} if it had already completed or been cancelled. Cancellation does not guarantee that the remote
     * AI service rolled back work already started.
     */
    boolean cancel();

    /** Whether this call has finished or been cancelled. */
    boolean isDone();
}
