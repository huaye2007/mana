package cn.managame.ai;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Application binding to external AI service processes (decision models, reasoning/memory LLMs, chat gateways).
 * Invoked concurrently up to each service's configured limits; route by {@link AiRequest#service()}.
 * Implementations must bound I/O and honor interruption, cancellation handles, and the remaining timeout.
 */
public interface AiTransport {

    /** Receiver for non-blocking streaming transports. Safe to call from any I/O thread. */
    interface StreamObserver {
        /** Deliver one encoded chunk JSON. Returns {@code false} if the stream is already stopped or cancelled. */
        boolean onChunk(String chunkJson);
        /** Complete the stream normally. */
        void onComplete();
        /** Fail the stream with {@code error}. */
        void onError(Throwable error);
    }

    /** Invoke {@code request} synchronously and return encoded JSON of the response. */
    String call(AiRequest request, Duration remaining) throws Exception;

    /**
     * Optional non-blocking hook (for RPC or async HTTP). Return {@code null} (the default) to run the blocking
     * {@link #call} on a bounded virtual thread; return a stage to avoid blocking a worker thread.
     */
    default CompletionStage<String> callAsync(AiRequest request, Duration remaining) {
        return null;
    }

    /**
     * Optional blocking streaming invocation: pass each encoded chunk JSON to {@code onChunk} as it arrives,
     * honoring interruption and {@code remaining}. Defaults to unsupported.
     */
    default void stream(AiRequest request, Duration remaining, Consumer<String> onChunk) throws Exception {
        throw new UnsupportedOperationException("Streaming is not supported by " + getClass().getName());
    }

    /**
     * Optional non-blocking streaming hook (for Netty SSE, async HTTP, or gRPC streams). Return {@code null} (default)
     * to run blocking {@link #stream} on a bounded virtual thread; return a cancel handle to stream asynchronously
     * without holding a worker thread. {@code close()} is invoked on cancellation, timeout, replacement or shutdown
     * so the transport can abort upstream I/O immediately.
     */
    default AutoCloseable streamAsync(AiRequest request, Duration remaining, StreamObserver observer) {
        return null;
    }
}
