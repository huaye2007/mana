package cn.managame.runtime.http;

import cn.managame.network.http.HttpResponseCallback;
import io.netty.handler.codec.http.FullHttpRequest;

/** Runtime-owned frozen HTTP registry; bind to HttpServerBuilder.asyncHandler(runtime.http()::dispatch). */
@FunctionalInterface
public interface HttpDispatcher {
    /** Borrows input during this call and retains its own reference only for an accepted Route invocation. */
    void dispatch(FullHttpRequest request, HttpResponseCallback callback);
}
