package cn.managame.ai;

/**
 * Handle to an in-flight {@link AiClient} call or stream. Safe to use from any thread, for example to abort a
 * streaming NPC response when a player disconnects.
 */
public interface AiCall {
    /**
     * Cancels the call if it has not yet completed. Returns {@code true} if this invocation cancelled it; subsequent
     * calls return {@code false}. Cancellation delivers {@link cn.managame.FrameworkErrorCodes#AI_CANCELLED} to the
     * callback or stream listener at most once.
     */
    boolean cancel();

    /** Whether the call has completed, failed, or been cancelled. */
    boolean isDone();

    /** Whether the call ended because of {@link #cancel()} (or client close). */
    boolean isCancelled();
}
