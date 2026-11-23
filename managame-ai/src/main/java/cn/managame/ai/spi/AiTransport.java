package cn.managame.ai.spi;

import cn.managame.FrameworkErrorCodes;
import cn.managame.ai.AiCall;
import cn.managame.ai.AiStreamListener;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound transport used by {@link cn.managame.ai.AiClient} to send requests to an external AI service.
 *
 * <p>The built-in implementation uses JDK {@link java.net.http.HttpClient} for HTTP/JSON and Server-Sent Events.
 * Applications may supply a custom transport for another protocol or for in-memory tests and benchmarks.
 */
public interface AiTransport extends AutoCloseable {
    /** Encoded outbound request handed to the transport. */
    record Request(String path, byte[] body, String contentType, Map<String, String> headers, Duration timeout) {}

    /**
     * Delivers the outcome of a unary transport call, exactly once. {@code errorCode == 0}
     * ({@link FrameworkErrorCodes#SUCCESS}) carries the raw response {@code body}; a positive error code carries a
     * diagnostic {@code errorMessage}.
     */
    @FunctionalInterface
    interface Callback {
        void onComplete(int errorCode, String body, String errorMessage);
    }

    /**
     * Sends a unary request and delivers its outcome asynchronously to {@code callback}, exactly once. Cancelling the
     * returned {@link AiCall} before completion must deliver {@link FrameworkErrorCodes#AI_CANCELLED}.
     */
    AiCall send(Request request, Callback callback);

    /**
     * Sends a streaming request and delivers chunks and termination asynchronously to {@code listener}. Cancelling the
     * returned {@link AiCall} before completion must deliver {@link FrameworkErrorCodes#AI_CANCELLED} to
     * {@link AiStreamListener#onError}.
     */
    AiCall stream(Request request, AiStreamListener listener);

    @Override default void close() {}
}
