package cn.managame.ai;

import cn.managame.FrameworkErrorCodes;

/**
 * Runs around every outbound {@link AiClient} call (unary, streaming and notify). Use it to attach dynamic headers
 * such as trace ids or request signatures, reject calls, or record audit logs.
 */
public interface AiClientInterceptor {
    /**
     * Runs before the request is sent. Return the request to send (possibly with added headers), or {@code null} to
     * abort the call with {@link FrameworkErrorCodes#AI_CANCELLED}.
     */
    default AiRequest before(AiRequest request) { return request; }

    /**
     * Runs once after the call finishes, in reverse interceptor order, for every interceptor whose {@link #before}
     * returned a non-null request. {@code errorCode} is {@link FrameworkErrorCodes#SUCCESS} on success.
     */
    default void after(AiRequest request, int errorCode, String message) {}
}
