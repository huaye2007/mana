package cn.managame.ai;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Application binding to external AI service processes (decision models, reasoning/memory LLMs, chat gateways).
 * Invoked concurrently up to each lane's configured limits. Implementations must bound I/O and honor interruption
 * and the remaining timeout.
 */
public interface AiTransport {
    /** Invoke {@code request} synchronously and return encoded JSON of the response. */
    String call(AiRequest request, Duration remaining) throws Exception;

    /**
     * Optional non-blocking hook (for RPC or async HTTP). Return {@code null} (the default) to run the blocking
     * {@link #call} on a bounded virtual thread; return a stage to avoid blocking a worker thread.
     * The stage remains bounded by its lane's concurrency limit and timeout.
     */
    default CompletionStage<String> callAsync(AiRequest request, Duration remaining) {
        return null;
    }

    /**
     * Optional streaming invocation: pass each encoded chunk JSON to {@code onChunk} as it arrives, honoring
     * interruption and {@code remaining}. Defaults to unsupported.
     */
    default void stream(AiRequest request, Duration remaining, Consumer<String> onChunk) throws Exception {
        throw new UnsupportedOperationException("Streaming is not supported by " + getClass().getName());
    }
}
