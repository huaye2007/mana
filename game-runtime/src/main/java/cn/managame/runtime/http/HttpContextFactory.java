package cn.managame.runtime.http;

import io.netty.handler.codec.http.FullHttpRequest;

/** Runs before Route submission; selects an unconfigured Key or supplies application-specific HTTP context fields. */
@FunctionalInterface
public interface HttpContextFactory {
    /**
     * Preserve the selected domain, request and callback. A nonzero selected key must also be preserved.
     * A selected key of zero means no annotation rule; the factory must then supply a nonzero key.
     * May reject by completing the callback and returning null. IllegalArgumentException maps to 400.
     * The request is borrowed here; any additional retained reference is owned by the application.
     */
    HttpContext create(int domain, long routeKey, FullHttpRequest request, HttpResultCallback callback);
}
